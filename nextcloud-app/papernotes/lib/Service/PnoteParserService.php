<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Service;

use Exception;
use InvalidArgumentException;
use RuntimeException;
use ZipArchive;

/**
 * Exception thrown when a .pnote archive is invalid, malformed, or exceeds security limits.
 */
class PnoteParseException extends Exception {
}

/**
 * Service to safely parse and validate .pnote files (ZIP containing manifest.json, preview.jpg, and pages/<uuid>.json).
 *
 * Implements strict security invariants:
 * - File size and entry size limits (anti-zip-bomb protection)
 * - Path traversal prevention (rejects '..', '\', leading '/', unknown paths)
 * - Strict regex validation for UUIDs, templates, and entry names
 * - Strict limits on pages, strokes per page, and points per stroke
 * - Safe numeric checks (finite floats, coordinate bounds)
 */
class PnoteParserService {
	public const int FORMAT_VERSION = 2;
	/** Smallest readable version; version 1 (view pixels instead of page coordinates) was never shipped. */
	public const int MIN_VERSION = 2;
	public const int MAX_TOTAL_BYTES = 67108864; // 64 MB
	public const int MAX_FILE_BYTES = 68157440; // 64 MB uncompressed content + 1 MB ZIP overhead
	public const int MAX_ENTRY_BYTES = 16777216; // 16 MB
	public const int MAX_PREVIEW_BYTES = 2097152; // 2 MB
	public const int MAX_ENTRIES = 2000;
	public const int MAX_PAGES = 5000;
	public const int MAX_STROKES_PER_PAGE = 200000;
	public const int MAX_POINTS_PER_STROKE = 20000;
	public const int MAX_PAGE_DIMENSION = 20000;
	public const int MAX_NAME_LENGTH = 200;
	public const int MAX_TEXTBOXES_PER_PAGE = 5000;
	public const int MAX_IMAGES_PER_PAGE = 500;
	public const int MAX_AUDIOS_PER_PAGE = 500;
	public const int MAX_TEXT_UNITS = 200000;
	public const int MAX_RICH_ENTRIES = 100000;
	public const int MAX_TITLE_LENGTH = 200;

	private const string UUID_REGEX = '/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/';
	private const string PAGE_ENTRY_REGEX = '/^pages\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.json$/';
	private const string MEDIA_ENTRY_REGEX = '/^media\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{1,8}$/';
	private const string MEDIA_FILE_REGEX = '/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]{1,8}$/';
	private const string TEMPLATE_REGEX = '/^[a-z0-9_]{1,32}$/';
	private const string IMPORT_SOURCE_REGEX = '/^[a-z]{1,16}:[0-9A-Za-z._-]{1,64}$/';

	/**
	 * Checks if a string is a valid lower-case UUID.
	 */
	public function isValidUuid(string $uuid): bool {
		return preg_match(self::UUID_REGEX, $uuid) === 1;
	}

	/**
	 * Checks if bytes start with JPEG magic bytes (FF D8 FF).
	 */
	public function isJpeg(string $bytes): bool {
		return strlen($bytes) >= 3
			&& ord($bytes[0]) === 0xFF
			&& ord($bytes[1]) === 0xD8
			&& ord($bytes[2]) === 0xFF;
	}

	/**
	 * Validates an entry name inside the ZIP archive.
	 */
	public function isAllowedEntryName(string $name, bool $isDirectory): bool {
		if ($name === '' || strlen($name) > self::MAX_NAME_LENGTH) {
			return false;
		}
		if (str_contains($name, '\\') || str_starts_with($name, '/') || str_contains($name, '..')) {
			return false;
		}
		if ($isDirectory) {
			return $name === 'pages/' || $name === 'media/';
		}
		return $name === 'manifest.json'
			|| $name === 'preview.jpg'
			|| $name === 'preview.png'
			|| preg_match(self::PAGE_ENTRY_REGEX, $name) === 1
			|| preg_match(self::MEDIA_ENTRY_REGEX, $name) === 1;
	}

	/**
	 * Parses a .pnote file from a filesystem path or stream and returns a validated array structure.
	 *
	 * @param string $filePath Absolute path to the .pnote file
	 * @param bool $manifestOnly If true, only reads manifest and summary
	 * @param bool $withMedia If true, the result carries the raw media files under 'media' (name => bytes); they are
	 *                        never part of the JSON sent to the browser
	 * @return array<string, mixed> Parsed note data
	 * @throws PnoteParseException
	 */
	public function parseFile(string $filePath, bool $manifestOnly = false, bool $withMedia = false): array {
		if (!file_exists($filePath) || !is_readable($filePath)) {
			throw new PnoteParseException('File not found or not readable');
		}

		$fileSize = filesize($filePath);
		if ($fileSize === false || $fileSize <= 0) {
			throw new PnoteParseException('File is empty or unreadable');
		}

		$zip = new ZipArchive();
		$openResult = $zip->open($filePath, ZipArchive::RDONLY);
		if ($openResult !== true) {
			throw new PnoteParseException('Invalid ZIP archive (error code: ' . $openResult . ')');
		}

		try {
			return $this->parseZipArchive($zip, $manifestOnly, $withMedia);
		} finally {
			$zip->close();
		}
	}

	/**
	 * Parses note content directly from binary string data.
	 *
	 * @param string $binaryData .pnote ZIP content
	 * @param bool $manifestOnly
	 * @param bool $withMedia see parseFile()
	 * @return array<string, mixed>
	 * @throws PnoteParseException
	 */
	public function parseString(string $binaryData, bool $manifestOnly = false, bool $withMedia = false): array {
		if ($binaryData === '') {
			throw new PnoteParseException('Empty data provided');
		}

		$tempFile = tempnam(sys_get_temp_dir(), 'pnote_');
		if ($tempFile === false) {
			throw new PnoteParseException('Failed to create temporary file for parsing');
		}

		try {
			if (file_put_contents($tempFile, $binaryData) === false) {
				throw new PnoteParseException('Failed to write temporary file');
			}
			return $this->parseFile($tempFile, $manifestOnly, $withMedia);
		} finally {
			if (file_exists($tempFile)) {
				@unlink($tempFile);
			}
		}
	}

	/**
	 * Extracts only the preview.jpg from a .pnote file if available.
	 *
	 * @param string $filePath
	 * @return string|null Binary JPEG data or null
	 */
	public function extractPreview(string $filePath): ?string {
		if (!file_exists($filePath) || !is_readable($filePath)) {
			return null;
		}

		$zip = new ZipArchive();
		if ($zip->open($filePath, ZipArchive::RDONLY) !== true) {
			return null;
		}

		try {
			$numFiles = $zip->numFiles;
			if ($numFiles > self::MAX_ENTRIES) {
				return null;
			}

			for ($i = 0; $i < $numFiles; $i++) {
				$stat = $zip->statIndex($i);
				if (!$stat) {
					continue;
				}
				$name = $stat['name'];
				$isDir = str_ends_with($name, '/');
				if (!$this->isAllowedEntryName($name, $isDir)) {
					return null;
				}
				if ($name === 'preview.jpg') {
					if ($stat['size'] > self::MAX_PREVIEW_BYTES) {
						return null;
					}
					$stream = $zip->getStream($name);
					if (!$stream) {
						return null;
					}
					$data = stream_get_contents($stream, self::MAX_PREVIEW_BYTES + 1);
					fclose($stream);
					if ($data !== false && strlen($data) <= self::MAX_PREVIEW_BYTES && $this->isJpeg($data)) {
						return $data;
					}
					return null;
				}
				if (str_starts_with($name, 'pages/')) {
					// preview.jpg must precede page entries
					break;
				}
			}
			return null;
		} catch (\Throwable) {
			return null;
		} finally {
			$zip->close();
		}
	}

	/**
	 * Extracts one media file (image or recording) from a .pnote file.
	 *
	 * @param string $filePath
	 * @param string $name File name below media/ (<uuid>.<ext>)
	 * @return string|null Binary data or null if missing, invalid or too large
	 */
	public function extractMedia(string $filePath, string $name): ?string {
		if (!$this->isValidMediaFile($name) || !file_exists($filePath) || !is_readable($filePath)) {
			return null;
		}

		$zip = new ZipArchive();
		if ($zip->open($filePath, ZipArchive::RDONLY) !== true) {
			return null;
		}

		try {
			if ($zip->numFiles > self::MAX_ENTRIES) {
				return null;
			}
			$stat = $zip->statName('media/' . $name);
			if (!$stat || $stat['size'] > self::MAX_ENTRY_BYTES) {
				return null;
			}
			$stream = $zip->getStream('media/' . $name);
			if (!$stream) {
				return null;
			}
			$data = stream_get_contents($stream, self::MAX_ENTRY_BYTES + 1);
			fclose($stream);
			return ($data !== false && strlen($data) <= self::MAX_ENTRY_BYTES) ? $data : null;
		} catch (\Throwable) {
			return null;
		} finally {
			$zip->close();
		}
	}

	/**
	 * Checks whether the size of a whole .pnote file is plausible, before it is copied anywhere.
	 */
	public function isAcceptableFileSize(int $bytes): bool {
		return $bytes > 0 && $bytes <= self::MAX_FILE_BYTES;
	}

	/**
	 * Checks a media file name as stored in pages (<uuid>.<ext>, no path).
	 */
	public function isValidMediaFile(string $name): bool {
		return preg_match(self::MEDIA_FILE_REGEX, $name) === 1;
	}

	/**
	 * Internal parser iterating over ZipArchive entries with strict size and name validation.
	 *
	 * @param ZipArchive $zip
	 * @param bool $manifestOnly
	 * @return array<string, mixed>
	 * @throws PnoteParseException
	 */
	private function parseZipArchive(ZipArchive $zip, bool $manifestOnly, bool $withMedia = false): array {
		$numFiles = $zip->numFiles;
		if ($numFiles > self::MAX_ENTRIES) {
			throw new PnoteParseException('ZIP contains too many entries (limit: ' . self::MAX_ENTRIES . ')');
		}

		$seenEntries = [];
		$manifestRaw = null;
		$previewRaw = null;
		$pageFiles = [];
		$mediaFiles = [];
		$totalUncompressedBytes = 0;

		for ($i = 0; $i < $numFiles; $i++) {
			$stat = $zip->statIndex($i);
			if (!$stat) {
				throw new PnoteParseException('Failed to read entry stat at index ' . $i);
			}

			$name = $stat['name'];
			$isDir = str_ends_with($name, '/');

			if (!$this->isAllowedEntryName($name, $isDir)) {
				throw new PnoteParseException('Invalid or prohibited ZIP entry name');
			}

			if (isset($seenEntries[$name])) {
				throw new PnoteParseException('Duplicate entry name in ZIP');
			}
			$seenEntries[$name] = true;

			if ($isDir) {
				continue;
			}

			// Pre-check uncompressed size from header if available
			$entrySize = $stat['size'];
			if ($entrySize > self::MAX_ENTRY_BYTES) {
				throw new PnoteParseException('ZIP entry exceeds maximum size');
			}

			$stream = $zip->getStream($name);
			if (!$stream) {
				throw new PnoteParseException('Failed to open stream for ZIP entry');
			}

			// Read with strict byte limit
			$limit = min(self::MAX_ENTRY_BYTES, self::MAX_TOTAL_BYTES - $totalUncompressedBytes);
			if ($limit <= 0) {
				fclose($stream);
				throw new PnoteParseException('Total uncompressed size exceeds maximum allowed (' . self::MAX_TOTAL_BYTES . ' bytes)');
			}

			$content = stream_get_contents($stream, $limit + 1);
			fclose($stream);

			if ($content === false) {
				throw new PnoteParseException('Error reading ZIP entry content');
			}

			$readLen = strlen($content);
			if ($readLen > $limit) {
				throw new PnoteParseException('ZIP entry exceeded size limit during reading');
			}

			$totalUncompressedBytes += $readLen;
			if ($totalUncompressedBytes > self::MAX_TOTAL_BYTES) {
				throw new PnoteParseException('Total uncompressed size limit exceeded');
			}

			if ($name === 'manifest.json') {
				$manifestRaw = $content;
				if ($manifestOnly) {
					break;
				}
			} elseif ($name === 'preview.jpg') {
				if ($readLen <= self::MAX_PREVIEW_BYTES && $this->isJpeg($content)) {
					$previewRaw = $content;
				}
			} elseif (preg_match(self::PAGE_ENTRY_REGEX, $name, $matches)) {
				$pageId = $matches[1];
				$pageFiles[$pageId] = $content;
			} elseif ($withMedia && str_starts_with($name, 'media/')) {
				$mediaFiles[substr($name, 6)] = $content;
			}
		}

		if ($manifestRaw === null) {
			throw new PnoteParseException('Missing manifest.json in .pnote archive');
		}

		$manifest = $this->parseManifest($manifestRaw);

		if ($manifestOnly) {
			return [
				'manifest' => $manifest,
				'pageCount' => count($manifest['pageIds']),
				'hasPreview' => $previewRaw !== null,
			];
		}

		// Parse all pages in the order defined by manifest.pageIds
		$pages = [];
		foreach ($manifest['pageIds'] as $pageId) {
			if (!isset($pageFiles[$pageId])) {
				throw new PnoteParseException('Page file missing for a page listed in the manifest');
			}
			$pages[$pageId] = $this->parsePage($pageId, $pageFiles[$pageId]);
		}

		$result = [
			'manifest' => $manifest,
			'pages' => $pages,
			'hasPreview' => $previewRaw !== null,
			'previewBase64' => $previewRaw !== null ? base64_encode($previewRaw) : null,
		];
		if ($withMedia) {
			$result['media'] = $mediaFiles;
		}
		return $result;
	}

	/**
	 * Parses and validates manifest.json.
	 *
	 * @param string $json
	 * @return array<string, mixed>
	 * @throws PnoteParseException
	 */
	public function parseManifest(string $json): array {
		try {
			$data = json_decode($json, true, 32, JSON_THROW_ON_ERROR);
		} catch (\JsonException) {
			throw new PnoteParseException('Corrupt manifest.json: invalid JSON');
		}
		if (!is_array($data)) {
			throw new PnoteParseException('Corrupt manifest.json: not a JSON object');
		}

		if (!isset($data['version']) || !is_int($data['version'])) {
			throw new PnoteParseException('Corrupt manifest: missing or invalid version');
		}

		$version = $data['version'];
		if ($version < self::MIN_VERSION) {
			throw new PnoteParseException('Corrupt manifest: version must be >= ' . self::MIN_VERSION);
		}
		if ($version > self::FORMAT_VERSION) {
			throw new PnoteParseException("Unsupported format version {$version} (highest supported is " . self::FORMAT_VERSION . ')');
		}

		if (!isset($data['id']) || !is_string($data['id']) || !$this->isValidUuid($data['id'])) {
			throw new PnoteParseException('Corrupt manifest: invalid note UUID');
		}

		if (!isset($data['title']) || !is_string($data['title'])) {
			throw new PnoteParseException('Corrupt manifest: missing or invalid title');
		}

		$folderPath = $data['folderPath'] ?? '';
		if (!is_string($folderPath)) {
			throw new PnoteParseException('Corrupt manifest: folderPath must be a string');
		}

		$created = isset($data['created']) && is_numeric($data['created']) ? (int)$data['created'] : 0;
		$modified = isset($data['modified']) && is_numeric($data['modified']) ? (int)$data['modified'] : 0;
		$favorite = !empty($data['favorite']);
		$pinned = !empty($data['pinned']);

		$importSource = null;
		if (isset($data['importSource']) && is_string($data['importSource'])) {
			if (preg_match(self::IMPORT_SOURCE_REGEX, $data['importSource']) === 1) {
				$importSource = $data['importSource'];
			}
		}

		if (!isset($data['pageIds']) || !is_array($data['pageIds'])) {
			throw new PnoteParseException('Corrupt manifest: missing or invalid pageIds list');
		}

		if (count($data['pageIds']) > self::MAX_PAGES) {
			throw new PnoteParseException('Exceeded maximum allowed pages limit: ' . count($data['pageIds']));
		}

		$seenPageIds = [];
		$validatedPageIds = [];
		foreach ($data['pageIds'] as $pageId) {
			if (!is_string($pageId) || !$this->isValidUuid($pageId)) {
				throw new PnoteParseException('Corrupt manifest: invalid page UUID');
			}
			if (isset($seenPageIds[$pageId])) {
				throw new PnoteParseException('Corrupt manifest: duplicate page UUID');
			}
			$seenPageIds[$pageId] = true;
			$validatedPageIds[] = $pageId;
		}

		return [
			'version' => $version,
			'id' => $data['id'],
			'title' => $data['title'],
			'folderPath' => $folderPath,
			'created' => $created,
			'modified' => $modified,
			'favorite' => $favorite,
			'pinned' => $pinned,
			'pageIds' => $validatedPageIds,
			'importSource' => $importSource,
		];
	}

	/**
	 * Parses and validates a single page JSON file.
	 *
	 * @param string $pageId
	 * @param string $json
	 * @return array<string, mixed>
	 * @throws PnoteParseException
	 */
	public function parsePage(string $pageId, string $json): array {
		try {
			$data = json_decode($json, true, 32, JSON_THROW_ON_ERROR);
		} catch (\JsonException) {
			throw new PnoteParseException('Corrupt page JSON: invalid JSON');
		}
		if (!is_array($data)) {
			throw new PnoteParseException('Corrupt page JSON: not a JSON object');
		}

		$template = $data['template'] ?? 'blank';
		if (!is_string($template) || preg_match(self::TEMPLATE_REGEX, $template) !== 1) {
			$template = 'blank';
		}

		$width = isset($data['width']) && is_numeric($data['width']) ? (int)$data['width'] : 1404;
		$height = isset($data['height']) && is_numeric($data['height']) ? (int)$data['height'] : 1872;

		if ($width < 1 || $width > self::MAX_PAGE_DIMENSION || $height < 1 || $height > self::MAX_PAGE_DIMENSION) {
			throw new PnoteParseException("Invalid page dimensions on page: {$width}x{$height}");
		}

		$bookmarked = !empty($data['bookmarked']);

		$rawStrokes = $data['strokes'] ?? [];
		if (!is_array($rawStrokes)) {
			throw new PnoteParseException('Corrupt strokes array on page');
		}

		if (count($rawStrokes) > self::MAX_STROKES_PER_PAGE) {
			throw new PnoteParseException('Exceeded max strokes per page limit on page');
		}

		$strokes = [];
		foreach ($rawStrokes as $s) {
			if (!is_array($s)) {
				continue;
			}

			$penType = isset($s['t']) && is_numeric($s['t']) ? (int)$s['t'] : 0;
			$strokeWidth = isset($s['w']) && is_numeric($s['w']) ? (float)$s['w'] : 1.0;
			if (!is_finite($strokeWidth) || $strokeWidth < 0.0) {
				$strokeWidth = 1.0;
			}

			$color = isset($s['c']) && is_numeric($s['c']) ? (int)$s['c'] : (int)0xFF000000;

			$rawPoints = $s['p'] ?? [];
			if (!is_array($rawPoints)) {
				continue;
			}

			if (count($rawPoints) > self::MAX_POINTS_PER_STROKE) {
				throw new PnoteParseException('Exceeded max points per stroke limit on page');
			}

			$points = [];
			foreach ($rawPoints as $pt) {
				if (!is_array($pt) || count($pt) < 2) {
					continue;
				}
				$x = (float)$pt[0];
				$y = (float)$pt[1];
				$time = isset($pt[2]) && is_numeric($pt[2]) ? (int)$pt[2] : 0;
				$pressure = isset($pt[3]) && is_numeric($pt[3]) ? (float)$pt[3] : 0.5;

				if (!is_finite($x) || !is_finite($y) || !is_finite($pressure)) {
					continue;
				}

				$points[] = [$x, $y, $time, $pressure];
			}

			$strokes[] = [
				't' => $penType,
				'w' => $strokeWidth,
				'c' => $color,
				'p' => $points,
			];
		}

		return [
			'id' => $pageId,
			'template' => $template,
			'width' => $width,
			'height' => $height,
			'bookmarked' => $bookmarked,
			'strokes' => $strokes,
			'textboxes' => $this->parseTextboxes($pageId, $data['textboxes'] ?? []),
			'images' => $this->parseImages($pageId, $data['images'] ?? []),
			'audios' => $this->parseAudios($pageId, $data['audios'] ?? []),
		];
	}

	/**
	 * Length of a string in UTF-16 code units (the unit of span ranges in the app).
	 */
	public function utf16Length(string $text): int {
		return intdiv(strlen((string)mb_convert_encoding($text, 'UTF-16LE', 'UTF-8')), 2);
	}

	/**
	 * @param mixed $raw
	 * @return array<int, array<string, mixed>>
	 * @throws PnoteParseException
	 */
	private function parseTextboxes(string $pageId, mixed $raw): array {
		if (!is_array($raw)) {
			return [];
		}
		if (count($raw) > self::MAX_TEXTBOXES_PER_PAGE) {
			throw new PnoteParseException('Exceeded max textboxes per page limit on page');
		}

		$out = [];
		foreach ($raw as $t) {
			if (!is_array($t)) {
				continue;
			}
			$text = isset($t['text']) && is_string($t['text']) ? $t['text'] : '';
			$units = $this->utf16Length($text);
			if ($units > self::MAX_TEXT_UNITS) {
				throw new PnoteParseException('Textbox text too long on page');
			}

			$x = $this->finiteFloat($t['x'] ?? 0);
			$y = $this->finiteFloat($t['y'] ?? 0);
			$w = $this->finiteFloat($t['w'] ?? 0);
			$h = $this->finiteFloat($t['h'] ?? 0);
			$size = $this->finiteFloat($t['size'] ?? 20, 20.0);
			if ($w <= 0.0 || $h <= 0.0) {
				continue;
			}
			$size = min(1000.0, max(1.0, $size));
			$bg = isset($t['bgColor']) && is_numeric($t['bgColor']) ? (int)$t['bgColor'] : 0;

			$spans = [];
			$rawSpans = $t['spans'] ?? [];
			if (is_array($rawSpans)) {
				if (count($rawSpans) > self::MAX_RICH_ENTRIES) {
					throw new PnoteParseException('Too many text spans on page');
				}
				foreach ($rawSpans as $sp) {
					if (!is_array($sp) || count($sp) < 4 || !is_numeric($sp[0]) || !is_numeric($sp[1]) || !is_numeric($sp[2]) || !is_numeric($sp[3])) {
						continue;
					}
					$type = (int)$sp[0];
					$start = max(0, (int)$sp[1]);
					$end = min($units, (int)$sp[2]);
					if ($type < 0 || $type > 4 || $end <= $start) {
						continue;
					}
					$spans[] = [$type, $start, $end, (int)$sp[3]];
				}
			}

			$paras = [];
			$rawParas = $t['paras'] ?? [];
			if (is_array($rawParas)) {
				if (count($rawParas) > self::MAX_RICH_ENTRIES) {
					throw new PnoteParseException('Too many paragraph formats on page');
				}
				foreach ($rawParas as $pf) {
					if (!is_array($pf) || count($pf) < 4 || !is_numeric($pf[0]) || !is_numeric($pf[1]) || !is_numeric($pf[2]) || !is_numeric($pf[3])) {
						continue;
					}
					$idx = (int)$pf[0];
					if ($idx < 0) {
						continue;
					}
					$paras[$idx] = [
						$idx,
						min(2, max(0, (int)$pf[1])),
						min(4, max(0, (int)$pf[2])),
						min(4, max(0, (int)$pf[3])),
					];
				}
				ksort($paras);
				$paras = array_values($paras);
			}

			$out[] = [
				'text' => $text,
				'x' => $x,
				'y' => $y,
				'w' => $w,
				'h' => $h,
				'size' => $size,
				'bgColor' => $bg,
				'spans' => $spans,
				'paras' => $paras,
			];
		}
		return $out;
	}

	/**
	 * @param mixed $raw
	 * @return array<int, array<string, mixed>>
	 * @throws PnoteParseException
	 */
	private function parseImages(string $pageId, mixed $raw): array {
		if (!is_array($raw)) {
			return [];
		}
		if (count($raw) > self::MAX_IMAGES_PER_PAGE) {
			throw new PnoteParseException('Exceeded max images per page limit on page');
		}

		$out = [];
		foreach ($raw as $i) {
			if (!is_array($i) || !isset($i['file']) || !is_string($i['file']) || !$this->isValidMediaFile($i['file'])) {
				continue;
			}
			$w = $this->finiteFloat($i['w'] ?? 0);
			$h = $this->finiteFloat($i['h'] ?? 0);
			if ($w <= 0.0 || $h <= 0.0) {
				continue;
			}
			$out[] = [
				'file' => $i['file'],
				'x' => $this->finiteFloat($i['x'] ?? 0),
				'y' => $this->finiteFloat($i['y'] ?? 0),
				'w' => $w,
				'h' => $h,
			];
		}
		return $out;
	}

	/**
	 * @param mixed $raw
	 * @return array<int, array<string, mixed>>
	 * @throws PnoteParseException
	 */
	private function parseAudios(string $pageId, mixed $raw): array {
		if (!is_array($raw)) {
			return [];
		}
		if (count($raw) > self::MAX_AUDIOS_PER_PAGE) {
			throw new PnoteParseException('Exceeded max audios per page limit on page');
		}

		$out = [];
		foreach ($raw as $a) {
			if (!is_array($a) || !isset($a['file']) || !is_string($a['file']) || !$this->isValidMediaFile($a['file'])) {
				continue;
			}
			$title = isset($a['title']) && is_string($a['title']) ? $a['title'] : '';
			if (mb_strlen($title) > self::MAX_TITLE_LENGTH) {
				$title = mb_substr($title, 0, self::MAX_TITLE_LENGTH);
			}
			$duration = isset($a['duration']) && is_numeric($a['duration']) ? max(0, (int)$a['duration']) : 0;
			$out[] = [
				'file' => $a['file'],
				'title' => $title,
				'duration' => $duration,
			];
		}
		return $out;
	}

	private function finiteFloat(mixed $value, float $default = 0.0): float {
		if (!is_numeric($value)) {
			return $default;
		}
		$f = (float)$value;
		return is_finite($f) ? $f : $default;
	}
}
