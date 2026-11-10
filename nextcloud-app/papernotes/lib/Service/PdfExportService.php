<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Service;

use Exception;

/**
 * Service to generate high-quality vector PDF documents from parsed PaperNotes data.
 *
 * Implements:
 * - Precise vector stroke rendering (round caps, round joins, exact width and ARGB colors)
 * - Complete geometry calculation and rendering for all 21 PaperNotes templates
 * - Automatic page scaling to standard printable PDF dimensions (A4 portrait width: 595pt)
 * - Images and text boxes (rich text) (B1); recordings are never drawn on the page, they can optionally be embedded
 * - Pure PHP implementation with PDF 1.4 specification and zlib stream compression (zero external dependencies)
 */
class PdfExportService {
	public const int PAGE_WIDTH_PT = 595;
	public const int MAX_DIMENSION_PT = 14400;
	public const int MAX_IMAGE_EDGE_PX = 2400;
	public const int MAX_IMAGE_PIXELS = 50000000;
	public const int MAX_EMBEDDED_BYTES = 50331648; // 48 MB of embedded recordings in total

	private PdfTextRenderer $text;

	public function __construct() {
		$this->text = new PdfTextRenderer();
	}

	private const array TEMPLATE_LABELS_DE = [
		'DONE' => '✓',
		'TIME' => 'Zeit',
		'TODOS' => 'Aufgaben',
		'SENDER' => 'Absender',
		'RECIPIENT' => 'Empfänger',
		'SUBJECT' => 'Betreff',
		'DATE' => 'Datum',
		'TITLE' => 'Titel',
		'PARTICIPANTS' => 'Teilnehmer',
		'AGENDA' => 'Tagesordnung',
		'NOTES' => 'Notizen',
		'DECISIONS' => 'Beschlüsse',
		'MON' => 'Mo',
		'TUE' => 'Di',
		'WED' => 'Mi',
		'THU' => 'Do',
		'FRI' => 'Fr',
		'SAT' => 'Sa',
		'SUN' => 'So',
		'TASK' => 'Aufgabe',
		'PRIORITY' => 'Priorität',
		'DUE' => 'Fällig',
		'ERROR' => 'Fehler',
		'CAUSE' => 'Ursache',
		'SOLUTION' => 'Lösung',
		'AUTHOR' => 'Autor',
		'QUOTES' => 'Zitate',
		'CUES' => 'Stichworte',
		'SUMMARY' => 'Zusammenfassung',
		'TOPIC' => 'Thema',
		'URGENT' => 'Dringend',
		'NOT_URGENT' => 'Nicht dringend',
		'IMPORTANT' => 'Wichtig',
		'NOT_IMPORTANT' => 'Nicht wichtig',
		'STRENGTHS' => 'Stärken',
		'WEAKNESSES' => 'Schwächen',
		'OPPORTUNITIES' => 'Chancen',
		'THREATS' => 'Risiken',
		'POINT' => 'Punkt',
		'ACTION' => 'Aktion',
	];

	private const array TEMPLATE_LABELS_EN = [
		'DONE' => '✓',
		'TIME' => 'Time',
		'TODOS' => 'Todos',
		'SENDER' => 'Sender',
		'RECIPIENT' => 'Recipient',
		'SUBJECT' => 'Subject',
		'DATE' => 'Date',
		'TITLE' => 'Title',
		'PARTICIPANTS' => 'Participants',
		'AGENDA' => 'Agenda',
		'NOTES' => 'Notes',
		'DECISIONS' => 'Decisions',
		'MON' => 'Mon',
		'TUE' => 'Tue',
		'WED' => 'Wed',
		'THU' => 'Thu',
		'FRI' => 'Fri',
		'SAT' => 'Sat',
		'SUN' => 'Sun',
		'TASK' => 'Task',
		'PRIORITY' => 'Priority',
		'DUE' => 'Due',
		'ERROR' => 'Error',
		'CAUSE' => 'Cause',
		'SOLUTION' => 'Solution',
		'AUTHOR' => 'Author',
		'QUOTES' => 'Quotes',
		'CUES' => 'Cues',
		'SUMMARY' => 'Summary',
		'TOPIC' => 'Topic',
		'URGENT' => 'Urgent',
		'NOT_URGENT' => 'Not urgent',
		'IMPORTANT' => 'Important',
		'NOT_IMPORTANT' => 'Not important',
		'STRENGTHS' => 'Strengths',
		'WEAKNESSES' => 'Weaknesses',
		'OPPORTUNITIES' => 'Opportunities',
		'THREATS' => 'Threats',
		'POINT' => 'Point',
		'ACTION' => 'Action',
	];

	/**
	 * Computes PDF page dimension in points and scale factor from note page dimensions.
	 *
	 * @param int $pageWidth Page width in note coordinates (e.g. 1404)
	 * @param int $pageHeight Page height in note coordinates (e.g. 1872)
	 * @return array{widthPt: int, heightPt: int, scale: float}
	 */
	public function calculatePageSize(int $pageWidth, int $pageHeight): array {
		$w = max(1, $pageWidth);
		$h = max(1, $pageHeight);

		$scale = (float)self::PAGE_WIDTH_PT / $w;
		$height = (int)round($h * $scale);

		if ($height <= self::MAX_DIMENSION_PT) {
			return [
				'widthPt' => self::PAGE_WIDTH_PT,
				'heightPt' => max(1, $height),
				'scale' => $scale,
			];
		}

		$limitedScale = (float)self::MAX_DIMENSION_PT / $h;
		return [
			'widthPt' => max(1, (int)round($w * $limitedScale)),
			'heightPt' => self::MAX_DIMENSION_PT,
			'scale' => $limitedScale,
		];
	}

	/**
	 * Generates a complete PDF binary string from parsed note data.
	 *
	 * @param array<string, mixed> $note Parsed note structure from PnoteParserService
	 * @param string $lang 'de' or 'en'
	 * @param bool $withAudio attach the recordings of each page as file attachments (nothing is drawn on the page)
	 * @return string PDF file content
	 * @throws Exception
	 */
	public function exportNoteToPdf(array $note, string $lang = 'de', bool $withAudio = false): string {
		$manifest = $note['manifest'] ?? [];
		$pages = $note['pages'] ?? [];
		$title = (string)($manifest['title'] ?? 'Note');
		$pageIds = $manifest['pageIds'] ?? array_keys($pages);
		$media = is_array($note['media'] ?? null) ? $note['media'] : [];
		$embeddedBytes = 0;

		if (empty($pageIds)) {
			throw new Exception('Cannot export empty note without pages');
		}

		$labels = ($lang === 'en') ? self::TEMPLATE_LABELS_EN : self::TEMPLATE_LABELS_DE;

		$writer = new SimplePdfWriter($title);

		foreach ($pageIds as $pageId) {
			$page = $pages[$pageId] ?? null;
			if (!$page) {
				continue;
			}

			$pw = (int)($page['width'] ?? 1404);
			$ph = (int)($page['height'] ?? 1872);
			$size = $this->calculatePageSize($pw, $ph);
			$widthPt = $size['widthPt'];
			$heightPt = $size['heightPt'];
			$scale = $size['scale'];

			$stream = '';

			// 1. Draw page background template
			$template = (string)($page['template'] ?? 'blank');
			$templateShapes = $this->buildTemplateShapes($template, $pw, $ph);
			$stream .= $this->renderTemplateShapesToPdf($templateShapes, $heightPt, $scale, $labels);

			// 2. Draw images (below the strokes, as in the editor)
			$imageNames = [];
			$imageStream = '';
			foreach (($page['images'] ?? []) as $img) {
				$file = (string)$img['file'];
				if (!isset($media[$file])) {
					continue;
				}
				$name = $writer->addImage($file, fn() => $this->prepareImage((string)$media[$file]));
				if ($name === null) {
					continue;
				}
				$imageNames[$name] = true;
				$imageStream .= sprintf(
					"q %.2f 0 0 %.2f %.2f %.2f cm /%s Do Q\n",
					(float)$img['w'] * $scale,
					(float)$img['h'] * $scale,
					(float)$img['x'] * $scale,
					$heightPt - ((float)$img['y'] + (float)$img['h']) * $scale,
					$name
				);
			}
			$stream .= $imageStream;

			// 3. Draw user strokes
			$strokes = $page['strokes'] ?? [];
			$stream .= $this->renderStrokesToPdf($strokes, $heightPt, $scale);

			// 4. Text boxes above the strokes
			foreach (($page['textboxes'] ?? []) as $tb) {
				$stream .= $this->text->renderTextbox($tb, $scale, (float)$heightPt);
			}

			// 5. Recordings are not drawn. On request they are attached to the page (small icon of the PDF viewer, top right).
			$attachments = [];
			if ($withAudio) {
				$iconPt = 20.0;
				$top = 8.0;
				foreach (($page['audios'] ?? []) as $audio) {
					$file = (string)$audio['file'];
					$data = $media[$file] ?? null;
					if (!is_string($data) || $data === '' || $embeddedBytes + strlen($data) > self::MAX_EMBEDDED_BYTES) {
						continue;
					}
					$embeddedBytes += strlen($data);
					$title = trim((string)$audio['title']);
					$ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
					$right = (float)$widthPt - 8.0;
					$attachments[] = [
						'rect' => [$right - $iconPt, (float)$heightPt - $top - $iconPt, $right, (float)$heightPt - $top],
						'name' => ($title !== '' ? $title : 'Audio') . '.' . $ext,
						'mime' => $ext === 'm4a' || $ext === 'mp4' ? 'audio/mp4' : 'application/octet-stream',
						'data' => $data,
						'desc' => $title !== '' ? $title : 'Audio',
					];
					$top += $iconPt + 4.0;
				}
			}

			$writer->addPage($widthPt, $heightPt, $stream, array_keys($imageNames), $attachments);
		}

		return $writer->build();
	}

	/**
	 * Converts an image file into data the PDF can embed: a baseline/progressive JPEG is passed through unchanged; every other
	 * format (PNG, GIF, WebP, ...) is flattened onto white and re-encoded as JPEG with GD. Returns null if the image is unusable.
	 *
	 * @return array{w: int, h: int, cs: string, data: string}|null
	 */
	public function prepareImage(string $bytes): ?array {
		$info = @getimagesizefromstring($bytes);
		if ($info === false) {
			return null;
		}
		$w = (int)$info[0];
		$h = (int)$info[1];
		if ($w < 1 || $h < 1 || $w * $h > self::MAX_IMAGE_PIXELS) {
			return null;
		}

		$channels = (int)($info['channels'] ?? 3);
		if ($info[2] === IMAGETYPE_JPEG && ($channels === 1 || $channels === 3) && (int)($info['bits'] ?? 8) === 8) {
			return ['w' => $w, 'h' => $h, 'cs' => $channels === 1 ? '/DeviceGray' : '/DeviceRGB', 'data' => $bytes];
		}

		if (!function_exists('imagecreatefromstring') || !function_exists('imagejpeg')) {
			return null;
		}
		$src = @imagecreatefromstring($bytes);
		if ($src === false) {
			return null;
		}
		try {
			$dw = $w;
			$dh = $h;
			$longest = max($w, $h);
			if ($longest > self::MAX_IMAGE_EDGE_PX) {
				$f = self::MAX_IMAGE_EDGE_PX / $longest;
				$dw = max(1, (int)round($w * $f));
				$dh = max(1, (int)round($h * $f));
			}
			$dst = imagecreatetruecolor($dw, $dh);
			if ($dst === false) {
				return null;
			}
			try {
				imagefill($dst, 0, 0, (int)imagecolorallocate($dst, 255, 255, 255));
				imagecopyresampled($dst, $src, 0, 0, 0, 0, $dw, $dh, $w, $h);
				ob_start();
				$ok = imagejpeg($dst, null, 88);
				$jpeg = (string)ob_get_clean();
			} finally {
				unset($dst);
			}
			if (!$ok || $jpeg === '') {
				return null;
			}
			return ['w' => $dw, 'h' => $dh, 'cs' => '/DeviceRGB', 'data' => $jpeg];
		} finally {
			unset($src);
		}
	}

	/**
	 * Renders template shapes (fine lines, main lines, head lines, dots, circles, labels) into PDF vector commands.
	 *
	 * @param array<string, mixed> $shapes
	 * @param int $heightPt
	 * @param float $scale
	 * @param array<string, string> $labels
	 * @return string
	 */
	private function renderTemplateShapesToPdf(array $shapes, int $heightPt, float $scale, array $labels): string {
		$out = "q\n";

		// Draw white background
		$out .= "1.0 1.0 1.0 rg\n";
		$out .= sprintf("0 0 10000 10000 re f\n");

		// Helper to map (x, y) in note coords to PDF (pdfX, pdfY)
		$toPdf = function(float $x, float $y) use ($heightPt, $scale): array {
			return [$x * $scale, $heightPt - ($y * $scale)];
		};

		// 1. Fine lines (#DDDDDD, 0.5pt)
		if (!empty($shapes['fine'])) {
			$out .= "0.867 0.867 0.867 RG\n"; // #DDDDDD
			$out .= sprintf("%.2f w\n", max(0.25, 0.4 * $scale * 1.4));
			$out .= "1 J 1 j\n";
			$fine = $shapes['fine'];
			$len = count($fine);
			for ($i = 0; $i + 3 < $len; $i += 4) {
				[$x1, $y1] = $toPdf($fine[$i], $fine[$i + 1]);
				[$x2, $y2] = $toPdf($fine[$i + 2], $fine[$i + 3]);
				$out .= sprintf("%.2f %.2f m %.2f %.2f l S\n", $x1, $y1, $x2, $y2);
			}
		}

		// 2. Main lines (#B0B0B0, 0.75pt)
		if (!empty($shapes['main'])) {
			$out .= "0.690 0.690 0.690 RG\n"; // #B0B0B0
			$out .= sprintf("%.2f w\n", max(0.35, 0.65 * $scale * 1.4));
			$out .= "1 J 1 j\n";
			$main = $shapes['main'];
			$len = count($main);
			for ($i = 0; $i + 3 < $len; $i += 4) {
				[$x1, $y1] = $toPdf($main[$i], $main[$i + 1]);
				[$x2, $y2] = $toPdf($main[$i + 2], $main[$i + 3]);
				$out .= sprintf("%.2f %.2f m %.2f %.2f l S\n", $x1, $y1, $x2, $y2);
			}
		}

		// 3. Head lines (#707070, 1.0pt)
		if (!empty($shapes['head'])) {
			$out .= "0.439 0.439 0.439 RG\n"; // #707070
			$out .= sprintf("%.2f w\n", max(0.5, 1.0 * $scale * 1.4));
			$out .= "1 J 1 j\n";
			$head = $shapes['head'];
			$len = count($head);
			for ($i = 0; $i + 3 < $len; $i += 4) {
				[$x1, $y1] = $toPdf($head[$i], $head[$i + 1]);
				[$x2, $y2] = $toPdf($head[$i + 2], $head[$i + 3]);
				$out .= sprintf("%.2f %.2f m %.2f %.2f l S\n", $x1, $y1, $x2, $y2);
			}
		}

		// 4. Dots (#909090)
		if (!empty($shapes['dots'])) {
			$out .= "0.565 0.565 0.565 rg\n"; // #909090
			$r = max(0.6, 1.3 * $scale * 1.4);
			$k = $r * 0.55228475;
			$dots = $shapes['dots'];
			$len = count($dots);
			for ($i = 0; $i + 1 < $len; $i += 2) {
				[$cx, $cy] = $toPdf($dots[$i], $dots[$i + 1]);
				$out .= sprintf(
					"%.2f %.2f m %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c f\n",
					$cx + $r, $cy,
					$cx + $r, $cy + $k, $cx + $k, $cy + $r, $cx, $cy + $r,
					$cx - $k, $cy + $r, $cx - $r, $cy + $k, $cx - $r, $cy,
					$cx - $r, $cy - $k, $cx - $k, $cy - $r, $cx, $cy - $r,
					$cx + $k, $cy - $r, $cx + $r, $cy - $k, $cx + $r, $cy
				);
			}
		}

		// 5. Circles (#A0A0A0)
		if (!empty($shapes['circles'])) {
			$out .= "0.627 0.627 0.627 RG\n"; // #A0A0A0
			$out .= sprintf("%.2f w\n", max(0.4, 0.8 * $scale * 1.4));
			$out .= "1 J 1 j\n";
			$circles = $shapes['circles'];
			$len = count($circles);
			for ($i = 0; $i + 2 < $len; $i += 3) {
				[$cx, $cy] = $toPdf($circles[$i], $circles[$i + 1]);
				$r = $circles[$i + 2] * $scale;
				$k = $r * 0.55228475;
				$out .= sprintf(
					"%.2f %.2f m %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c S\n",
					$cx + $r, $cy,
					$cx + $r, $cy + $k, $cx + $k, $cy + $r, $cx, $cy + $r,
					$cx - $k, $cy + $r, $cx - $r, $cy + $k, $cx - $r, $cy,
					$cx - $r, $cy - $k, $cx - $k, $cy - $r, $cx, $cy - $r,
					$cx + $k, $cy - $r, $cx + $r, $cy - $k, $cx + $r, $cy
				);
			}
		}

		// 6. Labels (Text in Helvetica)
		if (!empty($shapes['labels'])) {
			$out .= "0.502 0.502 0.502 rg\n"; // #808080
			$fontSizePt = max(5.0, 14.0 * $scale * 1.4 * 0.55);
			foreach ($shapes['labels'] as $item) {
				$labelKey = $item['label'];
				$text = $labels[$labelKey] ?? $labelKey;
				[$x, $y] = $toPdf((float)$item['x'], (float)$item['y']);
				$isCentered = !empty($item['centered']);
				$isRight = !empty($item['right']);

				// Approximate text width for centering in standard font
				$textLen = strlen($text);
				$approxWidth = $textLen * ($fontSizePt * 0.5);
				$drawX = $isCentered ? ($x - ($approxWidth / 2.0)) : ($isRight ? ($x - $approxWidth) : $x);

				// Escape PDF text string
				$escapedText = strtr($text, [
					'\\' => '\\\\',
					'(' => '\\(',
					')' => '\\)',
					'ä' => "\xE4",
					'ö' => "\xF6",
					'ü' => "\xFC",
					'Ä' => "\xC4",
					'Ö' => "\xD6",
					'Ü' => "\xDC",
					'ß' => "\xDF",
				]);

				$out .= sprintf("BT /F1 %.1f Tf %.2f %.2f Td (%s) Tj ET\n", $fontSizePt, $drawX, $y, $escapedText);
			}
		}

		$out .= "Q\n";
		return $out;
	}

	/**
	 * Renders vector strokes into PDF vector path commands.
	 *
	 * @param array<int, array<string, mixed>> $strokes
	 * @param int $heightPt
	 * @param float $scale
	 * @return string
	 */
	private function renderStrokesToPdf(array $strokes, int $heightPt, float $scale): string {
		if (empty($strokes)) {
			return '';
		}

		$out = "q\n1 J 1 j\n"; // Round cap, round join

		$lastColor = null;
		$lastWidth = null;
		$lastAlpha = null;

		foreach ($strokes as $s) {
			$points = $s['p'] ?? [];
			$ptCount = count($points);
			if ($ptCount === 0) {
				continue;
			}

			// Color (ARGB 32-bit int)
			$rawColor = (int)($s['c'] ?? 0xFF000000);
			$r = (($rawColor >> 16) & 0xFF) / 255.0;
			$g = (($rawColor >> 8) & 0xFF) / 255.0;
			$b = ($rawColor & 0xFF) / 255.0;

			// Marker strokes carry alpha 0x80 in the ARGB color; alpha 0 means "unset" (legacy), as in the viewer.
			$alphaByte = ($rawColor >> 24) & 0xFF;
			$alphaKey = ($alphaByte === 0 || $alphaByte === 255) ? 'GSO' : 'GSM';
			if ($alphaKey !== $lastAlpha) {
				$out .= "/{$alphaKey} gs\n";
				$lastAlpha = $alphaKey;
			}

			$colorKey = sprintf("%.3f %.3f %.3f", $r, $g, $b);
			if ($colorKey !== $lastColor) {
				$out .= sprintf("%.3f %.3f %.3f RG\n", $r, $g, $b);
				$lastColor = $colorKey;
			}

			// Width
			$rawWidth = (float)($s['w'] ?? 1.0);
			$strokeWidthPt = max(0.25, $rawWidth * $scale);
			$widthKey = sprintf("%.2f", $strokeWidthPt);
			if ($widthKey !== $lastWidth) {
				$out .= sprintf("%.2f w\n", $strokeWidthPt);
				$lastWidth = $widthKey;
			}

			// Path points
			$p0 = $points[0];
			$x0 = (float)$p0[0] * $scale;
			$y0 = $heightPt - ((float)$p0[1] * $scale);

			$out .= sprintf("%.2f %.2f m ", $x0, $y0);

			if ($ptCount === 1) {
				$out .= sprintf("%.2f %.2f l S\n", $x0 + 0.1, $y0);
			} else {
				for ($i = 1; $i < $ptCount; $i++) {
					$p = $points[$i];
					$xi = (float)$p[0] * $scale;
					$yi = $heightPt - ((float)$p[1] * $scale);
					$out .= sprintf("%.2f %.2f l ", $xi, $yi);
					if ($i % 8 === 0) {
						$out .= "\n";
					}
				}
				$out .= "S\n";
			}
		}

		$out .= "Q\n";
		return $out;
	}

	/**
	 * Builds geometric elements of a template matching TemplateGeometry.kt in PaperNotes.
	 *
	 * @param string $templateId
	 * @param int $width Note page width (e.g. 1404)
	 * @param int $height Note page height (e.g. 1872)
	 * @return array<string, mixed>
	 */
	public function buildTemplateShapes(string $templateId, int $width, int $height): array {
		$w = (float)$width;
		$h = (float)$height;

		$dp = 1.41875;
		$mm = 8.937;
		$textPx = 20.0;

		$fine = [];
		$main = [];
		$head = [];
		$dots = [];
		$circles = [];
		$labels = [];

		// Maßstab der HiNote-Vorlagen (Entwurfsmaß 702 x 840, oben links verankert).
		$ds = $w / 702.0;
		$margin = min(12.0 * $dp, min($w, $h) / 10.0);
		$pad = min(6.0 * $dp, $margin);
		$fl = $margin;
		$ft = $margin;
		$fr = $w - $margin;
		$fb = $h - $margin;
		$fw = $fr - $fl;
		$fh = $fb - $ft;

		$addSeg = function(array &$target, float $x1, float $y1, float $x2, float $y2) use ($w, $h) {
			$target[] = max(0.0, min($w, $x1));
			$target[] = max(0.0, min($h, $y1));
			$target[] = max(0.0, min($w, $x2));
			$target[] = max(0.0, min($h, $y2));
		};

		$rect = function(int $kind, float $l, float $t, float $r, float $b) use (&$fine, &$main, &$head, $addSeg) {
			if ($kind === 0) {
				$addSeg($fine, $l, $t, $r, $t);
				$addSeg($fine, $l, $b, $r, $b);
				$addSeg($fine, $l, $t, $l, $b);
				$addSeg($fine, $r, $t, $r, $b);
			} elseif ($kind === 2) {
				$addSeg($head, $l, $t, $r, $t);
				$addSeg($head, $l, $b, $r, $b);
				$addSeg($head, $l, $t, $l, $b);
				$addSeg($head, $r, $t, $r, $b);
			} else {
				$addSeg($main, $l, $t, $r, $t);
				$addSeg($main, $l, $b, $r, $b);
				$addSeg($main, $l, $t, $l, $b);
				$addSeg($main, $r, $t, $r, $b);
			}
		};

		$hline = function(int $kind, float $y, float $x1, float $x2) use (&$fine, &$main, &$head, $addSeg) {
			if ($kind === 0) {
				$addSeg($fine, $x1, $y, $x2, $y);
			} elseif ($kind === 2) {
				$addSeg($head, $x1, $y, $x2, $y);
			} else {
				$addSeg($main, $x1, $y, $x2, $y);
			}
		};

		$vline = function(int $kind, float $x, float $y1, float $y2) use (&$fine, &$main, &$head, $addSeg) {
			if ($kind === 0) {
				$addSeg($fine, $x, $y1, $x, $y2);
			} elseif ($kind === 2) {
				$addSeg($head, $x, $y1, $x, $y2);
			} else {
				$addSeg($main, $x, $y1, $x, $y2);
			}
		};

		$addLabel = function(string $lbl, float $x, float $y, bool $centered, bool $right = false) use (&$labels, $w, $h) {
			$labels[] = [
				'label' => $lbl,
				'x' => max(0.0, min($w, $x)),
				'y' => max(0.0, min($h, $y)),
				'centered' => $centered,
				'right' => $right,
			];
		};

		$labelRight = function(string $lbl, float $r, float $t, float $cellH) use ($addLabel, $pad, $textPx) {
			$addLabel($lbl, $r - $pad, $t + $cellH / 2.0 + $textPx * 0.35, false, true);
		};

		$labelLeft = function(string $lbl, float $l, float $t, float $cellH) use ($addLabel, $pad, $textPx) {
			$addLabel($lbl, $l + $pad, $t + $cellH / 2.0 + $textPx * 0.35, false);
		};

		$labelCenter = function(string $lbl, float $l, float $r, float $t, float $cellH) use ($addLabel, $textPx) {
			$addLabel($lbl, ($l + $r) / 2.0, $t + $cellH / 2.0 + $textPx * 0.35, true);
		};

		$lines = function(int $kind, float $from, float $step, float $to, float $x1, float $x2) use ($hline) {
			$s = max(3.0, $step);
			for ($y = $from; $y < $to; $y += $s) {
				$hline($kind, $y, $x1, $x2);
			}
		};

		$table = function(float $hdrH, float $rowH, array $colXs, array $hdrLabels) use ($fh, $ft, $fl, $fr, $rect, $hline, $vline, $labelCenter): int {
			$rh = max(3.0, $rowH);
			$rows = (int)floor(($fh - $hdrH) / $rh);
			$bottom = $ft + $hdrH + $rows * $rh;
			$rect(1, $fl, $ft, $fr, $bottom);
			$hline(2, $ft + $hdrH, $fl, $fr);
			foreach ($colXs as $x) {
				$vline(1, (float)$x, $ft, $bottom);
			}
			for ($k = 1; $k < $rows; $k++) {
				$hline(1, $ft + $hdrH + $k * $rh, $fl, $fr);
			}
			$bounds = array_merge([$fl], $colXs, [$fr]);
			foreach ($hdrLabels as $idx => $lbl) {
				$labelCenter($lbl, $bounds[$idx], $bounds[$idx + 1], $ft, $hdrH);
			}
			return $rows;
		};

		switch ($templateId) {
			case 'lines_wide':
				$lines(1, 56.0 * $dp, 56.0 * $dp, $h, 0.0, $w);
				break;

			case 'lines_narrow':
				$lines(1, 36.0 * $dp, 36.0 * $dp, $h, 0.0, $w);
				break;

			case 'dotted':
				$step = max(3.0, 36.0 * $dp);
				$nx = (int)floor($w / $step);
				$ny = (int)floor($h / $step);
				if ($nx >= 1 && $ny >= 1) {
					$x0 = ($w - ($nx - 1) * $step) / 2.0;
					$y0 = ($h - ($ny - 1) * $step) / 2.0;
					for ($j = 0; $j < $ny; $j++) {
						for ($i = 0; $i < $nx; $i++) {
							$dots[] = $x0 + $i * $step;
							$dots[] = $y0 + $j * $step;
						}
					}
				}
				break;

			case 'handwriting':
				$step = 48.0 * $dp;
				$lines(1, $step, $step, $h, 0.0, $w);
				for ($y = $step; $y < $h; $y += $step) {
					$hline(0, $y - $step / 2.0, 0.0, $w);
				}
				$vline(1, min(60.0 * $dp, $w / 4.0), 0.0, $h);
				break;

			case 'millimeter':
				$step = max(3.0, $mm);
				$countX = (int)floor($w / $step);
				for ($i = 1; $i <= $countX; $i++) {
					$vline($i % 5 === 0 ? 1 : 0, $i * $step, 0.0, $h);
				}
				$countY = (int)floor($h / $step);
				for ($j = 1; $j <= $countY; $j++) {
					$hline($j % 5 === 0 ? 1 : 0, $j * $step, 0.0, $w);
				}
				break;

			case 'grid':
				$step = max(3.0, 5.0 * $mm);
				for ($x = $step; $x < $w; $x += $step) {
					$vline(1, $x, 0.0, $h);
				}
				for ($y = $step; $y < $h; $y += $step) {
					$hline(1, $y, 0.0, $w);
				}
				break;

			case 'letter':
				$l = 0.1 * $w;
				$r = 0.9 * $w;
				$mid = 0.55 * $w;
				$top = 0.05 * $h;
				$senderH = 28.0 * $dp;
				$rect(1, $l, $top, $mid, $top + $senderH);
				$labelLeft('SENDER', $l, $top, $senderH);
				$recTop = $top + $senderH;
				$recBottom = $recTop + 110.0 * $dp;
				$rect(1, $l, $recTop, $mid, $recBottom);
				$labelLeft('RECIPIENT', $l, $recTop, $senderH);
				$rect(1, 0.62 * $w, $top, $r, $top + $senderH);
				$labelLeft('DATE', 0.62 * $w, $top, $senderH);
				$subjectY = $recBottom + 44.0 * $dp;
				$hline(2, $subjectY, $l, $r);
				$addLabel('SUBJECT', $l + $pad, $subjectY - $pad, false);
				foreach ([1.0 / 3.0, 0.5, 2.0 / 3.0] as $frac) {
					$hline(1, $frac * $h, 0.0, min(12.0 * $dp, $w / 4.0));
				}
				break;

			case 'todo':
				$hdr = 44.0 * $dp;
				$rowH = 68.0 * $dp;
				$c1 = $fl + 0.09 * $fw;
				$c2 = $fl + 0.32 * $fw;
				$rows = $table($hdr, $rowH, [$c1, $c2], ['DONE', 'TIME', 'TODOS']);
				$circleR = min(11.0 * $dp, ($c1 - $fl) / 3.0);
				for ($k = 0; $k < $rows; $k++) {
					$circles[] = ($fl + $c1) / 2.0;
					$circles[] = $ft + $hdr + ($k + 0.5) * $rowH;
					$circles[] = $circleR;
				}
				break;

			case 'tasks':
				$hdr = 44.0 * $dp;
				$rowH = 56.0 * $dp;
				$c1 = $fl + 0.08 * $fw;
				$due = $fr - 0.16 * $fw;
				$prio = $due - 0.12 * $fw;
				$rows = $table($hdr, $rowH, [$c1, $prio, $due], ['DONE', 'TASK', 'PRIORITY', 'DUE']);
				$half = min(10.0 * $dp, ($c1 - $fl) / 3.0);
				for ($k = 0; $k < $rows; $k++) {
					$cy = $ft + $hdr + ($k + 0.5) * $rowH;
					$cx = ($fl + $c1) / 2.0;
					$rect(1, $cx - $half, $cy - $half, $cx + $half, $cy + $half);
				}
				break;

			case 'error_log':
				$hdr = 44.0 * $dp;
				$x1 = $fl + 0.14 * $fw;
				$rest = ($fr - $x1) / 3.0;
				$table($hdr, 64.0 * $dp, [$x1, $x1 + $rest, $x1 + 2.0 * $rest], ['DATE', 'ERROR', 'CAUSE', 'SOLUTION']);
				break;

			case 'meeting':
				$hdr = 44.0 * $dp;
				$sec = 30.0 * $dp;
				$row = 40.0 * $dp;
				$rect(1, $fl, $ft, $fr, $fb);
				$y1 = $ft + $hdr;
				$y2 = $y1 + $hdr;
				$hline(1, $y1, $fl, $fr);
				$labelLeft('TITLE', $fl, $ft, $hdr);
				$midX = ($fl + $fr) / 2.0;
				$vline(1, $midX, $y1, $y2);
				$labelLeft('DATE', $fl, $y1, $hdr);
				$labelLeft('PARTICIPANTS', $midX, $y1, $hdr);
				$hline(2, $y2, $fl, $fr);
				$labelLeft('AGENDA', $fl, $y2, $sec);
				$agendaEnd = $y2 + $sec + 5.0 * $row;
				$lines(0, $y2 + $sec + $row, $row, $agendaEnd, $fl, $fr);
				$decTop = max($agendaEnd + $sec, $fb - 0.22 * $fh);
				$hline(2, $agendaEnd, $fl, $fr);
				$labelLeft('NOTES', $fl, $agendaEnd, $sec);
				$lines(0, $agendaEnd + $sec + $row, $row, $decTop, $fl, $fr);
				$hline(2, $decTop, $fl, $fr);
				$labelLeft('DECISIONS', $fl, $decTop, $sec);
				$lines(0, $decTop + $sec + $row, $row, $fb, $fl, $fr);
				break;

			case 'weekly':
				$days = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
				$rowH = $fh / 7.0;
				$lc = $fl + 0.1 * $fw;
				$cell = 40.0 * $dp;
				$rect(1, $fl, $ft, $fr, $fb);
				$vline(1, $lc, $ft, $fb);
				foreach ($days as $idx => $day) {
					$top = $ft + $idx * $rowH;
					if ($idx > 0) {
						$hline(2, $top, $fl, $fr);
					}
					$labelCenter($day, $fl, $lc, $top, $cell);
					$lines(0, $top + $cell, $cell, $top + $rowH - 1.0, $lc, $fr);
				}
				break;

			case 'monthly':
				$days = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
				$titleH = 44.0 * $dp;
				$dayH = 32.0 * $dp;
				$gridTop = $ft + $titleH + $dayH;
				$colW = $fw / 7.0;
				$rowH = ($fb - $gridTop) / 6.0;
				$rect(1, $fl, $ft, $fr, $fb);
				$hline(1, $ft + $titleH, $fl, $fr);
				$hline(2, $gridTop, $fl, $fr);
				for ($c = 1; $c < 7; $c++) {
					$vline(1, $fl + $c * $colW, $ft + $titleH, $fb);
				}
				for ($r = 1; $r < 6; $r++) {
					$hline(1, $gridTop + $r * $rowH, $fl, $fr);
				}
				foreach ($days as $c => $day) {
					$labelCenter($day, $fl + $c * $colW, $fl + ($c + 1) * $colW, $ft + $titleH, $dayH);
				}
				break;

			case 'reading_notes':
				$hdr = 44.0 * $dp;
				$sec = 30.0 * $dp;
				$row = 40.0 * $dp;
				$rect(1, $fl, $ft, $fr, $fb);
				$y1 = $ft + $hdr;
				$y2 = $y1 + $hdr;
				$hline(1, $y1, $fl, $fr);
				$labelLeft('TITLE', $fl, $ft, $hdr);
				$midX = ($fl + $fr) / 2.0;
				$vline(1, $midX, $y1, $y2);
				$labelLeft('AUTHOR', $fl, $y1, $hdr);
				$labelLeft('DATE', $midX, $y1, $hdr);
				$hline(2, $y2, $fl, $fr);
				$labelLeft('QUOTES', $fl, $y2, $sec);
				$quotesEnd = $y2 + 0.4 * ($fb - $y2);
				$lines(0, $y2 + $sec + $row, $row, $quotesEnd, $fl, $fr);
				$hline(2, $quotesEnd, $fl, $fr);
				$labelLeft('NOTES', $fl, $quotesEnd, $sec);
				$lines(0, $quotesEnd + $sec + $row, $row, $fb, $fl, $fr);
				break;

			case 'cornell':
				$hdr = 56.0 * $dp;
				$sec = 30.0 * $dp;
				$cueX = $fl + 0.28 * $fw;
				$summaryTop = $fb - 0.2 * $fh;
				$rect(1, $fl, $ft, $fr, $fb);
				$hline(2, $ft + $hdr, $fl, $fr);
				$labelLeft('TOPIC', $fl, $ft, $hdr);
				$vline(1, $cueX, $ft + $hdr, $summaryTop);
				$hline(2, $summaryTop, $fl, $fr);
				$labelLeft('CUES', $fl, $ft + $hdr, $sec);
				$labelLeft('NOTES', $cueX, $ft + $hdr, $sec);
				$labelLeft('SUMMARY', $fl, $summaryTop, $sec);
				break;

			case 'character_grid':
				$x0 = 21.5 * $ds;
				$colW = 66.0 * $ds;
				$rowH = 67.0 * $ds;
				$pitch = 80.67 * $ds;
				$y0 = 23.5 * $ds;
				$dash = 4.0 * $ds;
				$dashPitch = 6.0 * $ds;
				for ($r = 0; $r < 10; $r++) {
					$top = $y0 + $r * $pitch;
					$bottom = $top + $rowH;
					$hline(1, $top, $x0, $x0 + 10 * $colW);
					$hline(1, $bottom, $x0, $x0 + 10 * $colW);
					for ($c = 0; $c <= 10; $c++) {
						$vline(1, $x0 + $c * $colW, $top, $bottom);
					}
					$cy = $top + $rowH / 2.0;
					for ($c = 0; $c < 10; $c++) {
						$left = $x0 + $c * $colW;
						$cx = $left + $colW / 2.0;
						for ($k = 0; $k < 11; $k++) {
							$a = $top + $k * $dashPitch;
							$vline(0, $cx, $a, min($a + $dash, $bottom));
							$b = $left + $k * $dashPitch;
							$hline(0, $cy, $b, min($b + $dash, $left + $colW));
						}
					}
				}
				break;

			case 'staff':
				$sx1 = 25.0 * $ds;
				$sx2 = 678.0 * $ds;
				for ($g = 0; $g < 10; $g++) {
					$top = (116.479 + $g * 72.78) * $ds;
					for ($j = 0; $j < 5; $j++) {
						$hline(1, $top + $j * 8.0 * $ds, $sx1, $sx2);
					}
				}
				break;

			case 'time_management':
				$vline(1, 351.0 * $ds, 62.0 * $ds, 772.0 * $ds);
				$hline(1, 420.0 * $ds, 0.0, $w);
				$labelLeft('URGENT', 351.0 * $ds, 40.0 * $ds, 40.0 * $ds);
				$labelLeft('NOT_URGENT', 351.0 * $ds, 761.0 * $ds, 40.0 * $ds);
				$labelLeft('NOT_IMPORTANT', 0.0, 418.0 * $ds, 40.0 * $ds);
				$labelRight('IMPORTANT', $w, 418.0 * $ds, 40.0 * $ds);
				break;

			case 'point_sheet':
				$pl = 24.0 * $ds;
				$pr = 681.0 * $ds;
				$pmid = 351.3 * $ds;
				$rect(1, $pl, 24.0 * $ds, $pr, 816.0 * $ds);
				$hline(1, 72.0 * $ds, $pl, $pr);
				$hline(2, 117.0 * $ds, $pl, $pr);
				$vline(1, $pmid, 72.0 * $ds, 816.0 * $ds);
				$labelCenter('POINT', $pl, $pmid, 72.0 * $ds, 45.0 * $ds);
				$labelCenter('ACTION', $pmid, $pr, 72.0 * $ds, 45.0 * $ds);
				break;

			case 'swot':
				$vline(1, 351.0 * $ds, 0.0, 840.0 * $ds);
				$hline(1, 420.0 * $ds, 0.0, $w);
				$labelLeft('STRENGTHS', 0.0, 8.0 * $ds, 44.0 * $ds);
				$labelLeft('WEAKNESSES', 351.0 * $ds, 8.0 * $ds, 44.0 * $ds);
				$labelLeft('OPPORTUNITIES', 0.0, 428.0 * $ds, 44.0 * $ds);
				$labelLeft('THREATS', 351.0 * $ds, 428.0 * $ds, 44.0 * $ds);
				break;

			case 'blank':
			default:
				break;
		}

		return [
			'fine' => $fine,
			'main' => $main,
			'head' => $head,
			'dots' => $dots,
			'circles' => $circles,
			'labels' => $labels,
		];
	}
}

/**
 * Lightweight, robust PDF 1.4 document builder in pure PHP.
 *
 * Fonts: /F1 Helvetica, /F2 Helvetica-Bold, /F3 Helvetica-Oblique, /F4 Helvetica-BoldOblique (standard fonts, WinAnsi).
 * Images are JPEG XObjects (/Im1, /Im2, ...); attachments become FileAttachment annotations with an embedded file.
 */
class SimplePdfWriter {
	private string $title;
	/** @var array<int, array{width: int, height: int, stream: string, images: array<int, string>, attachments: array<int, array<string, mixed>>}> */
	private array $pages = [];
	/** @var array<string, array{name: string, w: int, h: int, cs: string, data: string}|null> */
	private array $images = [];

	public function __construct(string $title = 'Note') {
		$this->title = $title;
	}

	/**
	 * Registers an image once per $key and returns its resource name (e.g. "Im1"), or null if it cannot be embedded.
	 *
	 * @param callable(): (array{w: int, h: int, cs: string, data: string}|null) $loader
	 */
	public function addImage(string $key, callable $loader): ?string {
		if (!array_key_exists($key, $this->images)) {
			$img = $loader();
			$this->images[$key] = $img === null ? null : ['name' => 'Im' . (count(array_filter($this->images)) + 1)] + $img;
		}
		return $this->images[$key]['name'] ?? null;
	}

	/**
	 * @param array<int, string> $imageNames resource names (from addImage) used by the page stream
	 * @param array<int, array{rect: array<int, float>, name: string, mime: string, data: string, desc: string}> $attachments
	 */
	public function addPage(int $widthPt, int $heightPt, string $pageStream, array $imageNames = [], array $attachments = []): void {
		$this->pages[] = [
			'width' => $widthPt,
			'height' => $heightPt,
			'stream' => $pageStream,
			'images' => $imageNames,
			'attachments' => $attachments,
		];
	}

	private function pdfString(string $s): string {
		return '(' . strtr($s, ['\\' => '\\\\', '(' => '\\(', ')' => '\\)', "\r" => '\\r', "\n" => '\\n']) . ')';
	}

	/**
	 * UTF-16BE text string with byte order mark, as a PDF hex string.
	 */
	private function pdfUnicode(string $s): string {
		return '<FEFF' . strtoupper(bin2hex((string)mb_convert_encoding($s, 'UTF-16BE', 'UTF-8'))) . '>';
	}

	public function build(): string {
		if (count($this->pages) === 0) {
			$this->addPage(595, 842, '');
		}

		/** @var array<int, string> $objs */
		$objs = [];
		$next = 1;
		$alloc = function () use (&$next): int {
			return $next++;
		};
		$streamObj = function (string $dict, string $data): string {
			return '<< ' . $dict . ' /Length ' . strlen($data) . " >>\nstream\n" . $data . "\nendstream";
		};

		$catalog = $alloc();
		$pagesNum = $alloc();
		$fonts = [];
		foreach (['Helvetica', 'Helvetica-Bold', 'Helvetica-Oblique', 'Helvetica-BoldOblique'] as $i => $base) {
			$n = $alloc();
			$fonts['F' . ($i + 1)] = $n;
			$objs[$n] = "<< /Type /Font /Subtype /Type1 /BaseFont /{$base} /Encoding /WinAnsiEncoding >>";
		}

		$imageObjs = [];
		foreach ($this->images as $img) {
			if ($img === null) {
				continue;
			}
			$n = $alloc();
			$imageObjs[$img['name']] = $n;
			$objs[$n] = $streamObj(
				"/Type /XObject /Subtype /Image /Width {$img['w']} /Height {$img['h']} /ColorSpace {$img['cs']} /BitsPerComponent 8 /Filter /DCTDecode",
				$img['data']
			);
		}

		$pageObjNums = [];
		foreach ($this->pages as $page) {
			$content = $alloc();
			$raw = $page['stream'];
			$compressed = function_exists('gzcompress') ? gzcompress($raw, 6) : false;
			if ($compressed !== false && strlen($compressed) < strlen($raw)) {
				$objs[$content] = $streamObj('/Filter /FlateDecode', $compressed);
			} else {
				$objs[$content] = $streamObj('', $raw);
			}

			$annots = [];
			foreach ($page['attachments'] as $att) {
				$fileObj = $alloc();
				$objs[$fileObj] = $streamObj(
					'/Type /EmbeddedFile /Subtype /' . str_replace('/', '#2F', $att['mime']) . ' /Params << /Size ' . strlen($att['data']) . ' >>',
					$att['data']
				);
				$specObj = $alloc();
				$asciiName = preg_replace('/[^A-Za-z0-9._ -]/u', '_', $att['name']) ?: 'audio';
				$objs[$specObj] = '<< /Type /Filespec /F ' . $this->pdfString($asciiName) . ' /UF ' . $this->pdfUnicode($att['name'])
					. ' /Desc ' . $this->pdfUnicode($att['desc']) . " /EF << /F {$fileObj} 0 R /UF {$fileObj} 0 R >> >>";
				$annotObj = $alloc();
				$r = $att['rect'];
				$objs[$annotObj] = sprintf(
					'<< /Type /Annot /Subtype /FileAttachment /Rect [ %.2f %.2f %.2f %.2f ] /Contents %s /FS %d 0 R /Name /PushPin /F 28 /Border [ 0 0 0 ] /C [ ] >>',
					$r[0], $r[1], $r[2], $r[3], $this->pdfUnicode($att['desc']), $specObj
				);
				$annots[] = "{$annotObj} 0 R";
			}

			$xobjects = '';
			foreach (array_unique($page['images']) as $name) {
				if (isset($imageObjs[$name])) {
					$xobjects .= "/{$name} {$imageObjs[$name]} 0 R ";
				}
			}
			$fontRes = '';
			foreach ($fonts as $name => $num) {
				$fontRes .= "/{$name} {$num} 0 R ";
			}

			$pageObj = $alloc();
			$pageObjNums[] = $pageObj;
			$objs[$pageObj] = "<< /Type /Page /Parent {$pagesNum} 0 R /MediaBox [ 0 0 {$page['width']} {$page['height']} ] "
				. "/Resources << /Font << {$fontRes}>> "
				. ($xobjects !== '' ? "/XObject << {$xobjects}>> " : '')
				. '/ExtGState << /GSO << /CA 1 /ca 1 >> /GSM << /CA 0.5 /ca 0.5 >> >> >> '
				. (empty($annots) ? '' : '/Annots [ ' . implode(' ', $annots) . ' ] ')
				. "/Contents {$content} 0 R >>";
		}

		$kids = implode(' ', array_map(fn($num) => "{$num} 0 R", $pageObjNums));
		$objs[$pagesNum] = "<< /Type /Pages /Kids [ {$kids} ] /Count " . count($pageObjNums) . ' >>';
		$objs[$catalog] = "<< /Type /Catalog /Pages {$pagesNum} 0 R >>";

		$info = $alloc();
		$objs[$info] = '<< /Title ' . $this->pdfUnicode($this->title) . ' /Creator (PaperNotes by BOB) /Producer (PaperNotes Nextcloud Plugin) >>';

		$pdf = "%PDF-1.4\n%\xE2\xE3\xCF\xD3\n";
		$offsets = [];
		ksort($objs);
		foreach ($objs as $num => $content) {
			$offsets[$num] = strlen($pdf);
			$pdf .= "{$num} 0 obj\n{$content}\nendobj\n";
		}

		$startXref = strlen($pdf);
		$total = $info + 1;
		$pdf .= "xref\n0 {$total}\n";
		$pdf .= "0000000000 65535 f \n";
		for ($i = 1; $i < $total; $i++) {
			$pdf .= sprintf("%010d 00000 n \n", $offsets[$i] ?? 0);
		}
		$pdf .= "trailer\n<< /Size {$total} /Root {$catalog} 0 R /Info {$info} 0 R >>\n";
		$pdf .= "startxref\n{$startXref}\n%%EOF\n";

		return $pdf;
	}
}
