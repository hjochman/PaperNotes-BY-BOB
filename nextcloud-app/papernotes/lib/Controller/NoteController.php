<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Controller;

use Exception;
use OCA\PaperNotes\Service\PdfExportService;
use OCA\PaperNotes\Service\PnoteParseException;
use OCA\PaperNotes\Service\PnoteParserService;
use OCA\PaperNotes\Service\PnoteStorageService;
use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\DataDisplayResponse;
use OCP\AppFramework\Http\DataDownloadResponse;
use OCP\AppFramework\Http\DataResponse;
use OCP\AppFramework\Http\JSONResponse;
use OCP\AppFramework\Http\NotFoundResponse;
use OCP\Files\File;
use OCP\Files\NotFoundException;
use OCP\Files\NotPermittedException;
use OCP\IL10N;
use OCP\IRequest;
use OCP\IUserSession;
use Psr\Log\LoggerInterface;

/**
 * Controller handling .pnote data extraction, preview images, and vector PDF exports.
 */
class NoteController extends Controller {
	public function __construct(
		string $appName,
		IRequest $request,
		private PnoteStorageService $storageService,
		private PnoteParserService $parserService,
		private PdfExportService $pdfExportService,
		private IUserSession $userSession,
		private IL10N $l10n,
		private LoggerInterface $logger,
	) {
		parent::__construct($appName, $request);
	}

	/**
	 * Returns note titles and metadata for a list of file IDs, folder ID, or directory path.
	 *
	 * @NoCSRFRequired
	 * @NoAdminRequired
	 * @param string $ids Comma-separated list of file IDs
	 * @param int|null $folderId
	 * @param string|null $dir
	 * @return JSONResponse
	 */
	#[NoCSRFRequired]
	#[NoAdminRequired]
	public function getTitles(string $ids = '', ?int $folderId = null, ?string $dir = null): JSONResponse {
		$files = [];

		if ($folderId !== null || $dir !== null) {
			$folderFiles = $this->storageService->getNotesInFolder($folderId, $dir);
			foreach ($folderFiles as $f) {
				$files[$f->getId()] = $f;
			}
		}

		$idList = array_filter(array_map('intval', explode(',', $ids)));
		foreach (array_slice($idList, 0, 100) as $fileId) {
			if (!isset($files[$fileId])) {
				try {
					$files[$fileId] = $this->storageService->getFileById($fileId);
				} catch (NotFoundException|NotPermittedException) {
					// Not visible for this user: simply no title
				} catch (\Throwable $e) {
					$this->logger->warning('Resolving note for title failed', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
				}
			}
		}

		$result = [];
		foreach ($files as $fileId => $file) {
			try {
				$content = $file->getContent();
				$parsed = $this->parserService->parseString($content, true);
				$title = (string)($parsed['manifest']['title'] ?? '');
				if ($title === '') {
					$title = $file->getName();
				}
				$info = [
					'id' => $fileId,
					'name' => $file->getName(),
					'title' => $title,
					'folderPath' => (string)($parsed['manifest']['folderPath'] ?? ''),
					'pageCount' => count($parsed['manifest']['pageIds'] ?? []),
					'updatedAt' => $parsed['manifest']['updatedAt'] ?? $file->getMTime(),
				];
				$result[(string)$fileId] = $info;
				$result[$file->getName()] = $info;
				$base = str_ends_with(strtolower($file->getName()), '.pnote') ? substr($file->getName(), 0, -6) : $file->getName();
				$result[$base] = $info;
			} catch (\Throwable $e) {
				$this->logger->warning('Reading note title failed', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			}
		}

		return new JSONResponse([
			'status' => 'success',
			'titles' => (object)$result,
		]);
	}

	/**
	 * Returns parsed JSON structure of a .pnote notebook.
	 *
	 * @NoCSRFRequired
	 * @NoAdminRequired
	 * @param int $fileId
	 * @return JSONResponse
	 */
	#[NoCSRFRequired]
	#[NoAdminRequired]
	public function getNoteData(int $fileId): JSONResponse {
		try {
			$file = $this->storageService->getFileById($fileId);
			$content = $file->getContent();
			$parsed = $this->parserService->parseString($content, false);

			return new JSONResponse([
				'status' => 'success',
				'data' => $parsed,
				'file' => [
					'id' => $file->getId(),
					'name' => $file->getName(),
					'size' => $file->getSize(),
					'mtime' => $file->getMTime(),
				],
			]);
		} catch (NotFoundException) {
			return new JSONResponse(['status' => 'error', 'message' => 'File not found'], Http::STATUS_NOT_FOUND);
		} catch (NotPermittedException) {
			return new JSONResponse(['status' => 'error', 'message' => 'Permission denied'], Http::STATUS_FORBIDDEN);
		} catch (PnoteParseException $e) {
			$this->logger->warning('Malformed note archive', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			return new JSONResponse(['status' => 'error', 'message' => 'Malformed note archive'], Http::STATUS_UNPROCESSABLE_ENTITY);
		} catch (\Throwable $e) {
			$this->logger->error('Reading note failed', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			return new JSONResponse(['status' => 'error', 'message' => 'Internal server error'], Http::STATUS_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Extracts and serves preview.jpg embedded in the .pnote file.
	 *
	 * @NoCSRFRequired
	 * @NoAdminRequired
	 * @param int $fileId
	 * @return Http\Response
	 */
	#[NoCSRFRequired]
	#[NoAdminRequired]
	public function getPreview(int $fileId): Http\Response {
		try {
			$file = $this->storageService->getFileById($fileId);
			$temp = $this->copyToTempFile($file, 'prev_');

			try {
				$jpeg = $this->parserService->extractPreview($temp);
				if ($jpeg === null) {
					return new NotFoundResponse();
				}

				$response = new DataDisplayResponse($jpeg, Http::STATUS_OK, [
					'Content-Type' => 'image/jpeg',
					'Content-Length' => (string)strlen($jpeg),
					'Cache-Control' => 'public, max-age=86400, immutable',
					'ETag' => '"' . md5($jpeg) . '"',
				]);
				return $response;
			} finally {
				if (file_exists($temp)) {
					@unlink($temp);
				}
			}
		} catch (NotFoundException|NotPermittedException) {
			return new NotFoundResponse();
		} catch (\Throwable $e) {
			$this->logger->error('Reading note preview failed', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			return new NotFoundResponse();
		}
	}

	/**
	 * Serves one media file (image or recording) of a .pnote file.
	 *
	 * @NoCSRFRequired
	 * @NoAdminRequired
	 * @param int $fileId
	 * @param string $name File name below media/ (<uuid>.<ext>)
	 * @return Http\Response
	 */
	#[NoCSRFRequired]
	#[NoAdminRequired]
	public function getMedia(int $fileId, string $name): Http\Response {
		try {
			if (!$this->parserService->isValidMediaFile($name)) {
				return new NotFoundResponse();
			}
			$file = $this->storageService->getFileById($fileId);
			$temp = $this->copyToTempFile($file, 'media_');

			try {
				$data = $this->parserService->extractMedia($temp, $name);
				if ($data === null) {
					return new NotFoundResponse();
				}

				$ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
				$types = [
					'jpg' => 'image/jpeg',
					'jpeg' => 'image/jpeg',
					'png' => 'image/png',
					'gif' => 'image/gif',
					'webp' => 'image/webp',
					'm4a' => 'audio/mp4',
				];
				$type = $types[$ext] ?? 'application/octet-stream';

				return new DataDisplayResponse($data, Http::STATUS_OK, [
					'Content-Type' => $type,
					'Content-Length' => (string)strlen($data),
					'Content-Disposition' => isset($types[$ext]) ? 'inline' : 'attachment',
					'X-Content-Type-Options' => 'nosniff',
					'Content-Security-Policy' => "default-src 'none'; sandbox",
					'Cache-Control' => 'private, max-age=3600',
					'ETag' => '"' . md5($data) . '"',
				]);
			} finally {
				if (file_exists($temp)) {
					@unlink($temp);
				}
			}
		} catch (NotFoundException|NotPermittedException) {
			return new NotFoundResponse();
		} catch (\Throwable $e) {
			$this->logger->error('Reading note media failed', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			return new NotFoundResponse();
		}
	}

	/**
	 * Streams a note file into a temporary file (ZipArchive needs a path) without holding its content in memory.
	 * The size is checked before and while copying, so a manipulated file cannot fill /tmp.
	 *
	 * @param string $prefix Prefix of the temporary file name
	 * @return string Path of the temporary file; the caller deletes it
	 * @throws \RuntimeException
	 */
	private function copyToTempFile(File $file, string $prefix): string {
		if (!$this->parserService->isAcceptableFileSize((int)$file->getSize())) {
			throw new \RuntimeException('Note file size is not acceptable');
		}
		$temp = tempnam(sys_get_temp_dir(), $prefix);
		if ($temp === false) {
			throw new \RuntimeException('Cannot create temporary file');
		}

		$in = null;
		$out = null;
		try {
			$in = $file->fopen('rb');
			$out = fopen($temp, 'wb');
			if (!is_resource($in) || !is_resource($out)) {
				throw new \RuntimeException('Cannot open note file for copying');
			}
			$copied = stream_copy_to_stream($in, $out, PnoteParserService::MAX_FILE_BYTES + 1);
			if ($copied === false || $copied > PnoteParserService::MAX_FILE_BYTES) {
				throw new \RuntimeException('Copying note file failed or exceeded size limit');
			}
		} catch (\Throwable $e) {
			if (is_resource($in)) {
				fclose($in);
			}
			if (is_resource($out)) {
				fclose($out);
			}
			@unlink($temp);
			throw $e;
		}
		fclose($in);
		fclose($out);
		return $temp;
	}

	/**
	 * Generates and streams vector PDF export.
	 *
	 * @NoCSRFRequired
	 * @NoAdminRequired
	 * @param int $fileId
	 * @param string|null $lang
	 * @param string|null $audio "1" embeds the recordings of the note as file attachments
	 * @return Http\Response
	 */
	#[NoCSRFRequired]
	#[NoAdminRequired]
	public function exportPdf(int $fileId, ?string $lang = null, ?string $audio = null): Http\Response {
		try {
			$file = $this->storageService->getFileById($fileId);
			$content = $file->getContent();
			$parsed = $this->parserService->parseString($content, false, true);

			$selectedLang = $lang ?? ($this->l10n->getLanguageCode() === 'de' ? 'de' : 'en');
			$pdfData = $this->pdfExportService->exportNoteToPdf($parsed, $selectedLang, $audio === '1');

			$rawTitle = (string)($parsed['manifest']['title'] ?? '');
			$safeTitle = trim(preg_replace('/[^\p{L}\p{N}_\-\. ]/u', '_', $rawTitle) ?: 'Note');
			$filename = $safeTitle . '.pdf';

			return new DataDownloadResponse($pdfData, $filename, 'application/pdf');
		} catch (NotFoundException) {
			return new JSONResponse(['status' => 'error', 'message' => 'File not found'], Http::STATUS_NOT_FOUND);
		} catch (NotPermittedException) {
			return new JSONResponse(['status' => 'error', 'message' => 'Permission denied'], Http::STATUS_FORBIDDEN);
		} catch (PnoteParseException $e) {
			$this->logger->warning('Cannot export invalid note', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			return new JSONResponse(['status' => 'error', 'message' => 'Cannot export invalid note'], Http::STATUS_UNPROCESSABLE_ENTITY);
		} catch (\Throwable $e) {
			$this->logger->error('PDF export failed', ['app' => $this->appName, 'fileId' => $fileId, 'exception' => $e]);
			return new JSONResponse(['status' => 'error', 'message' => 'PDF export failed'], Http::STATUS_INTERNAL_SERVER_ERROR);
		}
	}
}
