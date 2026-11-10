<?php

declare(strict_types=1);

require_once __DIR__ . '/../lib/Service/PnoteParserService.php';
require_once __DIR__ . '/../lib/Service/PdfTextRenderer.php';
require_once __DIR__ . '/../lib/Service/PdfExportService.php';

use OCA\PaperNotes\Service\PnoteParseException;
use OCA\PaperNotes\Service\PnoteParserService;
use OCA\PaperNotes\Service\PdfExportService;

/**
 * Self-contained PHP unit tests for PaperNotes parser and PDF export services.
 */
class PaperNotesTestRunner {
	private int $passed = 0;
	private int $failed = 0;
	private PnoteParserService $parser;
	private PdfExportService $pdfExport;

	public function __construct() {
		$this->parser = new PnoteParserService();
		$this->pdfExport = new PdfExportService();
	}

	public function run(): bool {
		echo "=== Running PaperNotes Nextcloud Plugin Tests ===\n\n";

		$this->testValidNoteParsingAndPdf();
		$this->testPathTraversalProhibition();
		$this->testProhibitedEntryNames();
		$this->testMaxEntriesLimit();
		$this->testMaxTotalBytesLimit();
		$this->testCorruptManifests();
		$this->testAll21TemplatesExport();
		$this->testPreviewExtraction();
		$this->testRichPageContentParsing();
		$this->testPageContentLimitsAndSanitizing();
		$this->testMediaExtraction();
		$this->testPdfWithTextImageAudio();
		$this->testPdfAudioOption();
		$this->testTextRenderer();

		echo "\n================================================\n";
		echo "Results: {$this->passed} passed, {$this->failed} failed\n";
		return $this->failed === 0;
	}

	private function assert(bool $condition, string $testName, string $message = ''): void {
		if ($condition) {
			$this->passed++;
			echo "  [PASS] {$testName}\n";
		} else {
			$this->failed++;
			echo "  [FAIL] {$testName}: {$message}\n";
		}
	}

	private function createTestZip(array $entries): string {
		$tmp = tempnam(sys_get_temp_dir(), 'test_pnote_');
		$zip = new ZipArchive();
		$zip->open($tmp, ZipArchive::CREATE | ZipArchive::OVERWRITE);
		foreach ($entries as $name => $content) {
			$zip->addFromString($name, $content);
		}
		$zip->close();
		return $tmp;
	}

	private function testValidNoteParsingAndPdf(): void {
		$noteId = '11111111-2222-3333-4444-555555555555';
		$pageId1 = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
		$pageId2 = 'ffffffff-0000-1111-2222-333333333333';

		$manifest = json_encode([
			'version' => 2,
			'id' => $noteId,
			'title' => 'Test Notiz Äpfel',
			'folderPath' => 'Arbeit/Projekt',
			'created' => 1700000000000,
			'modified' => 1700000050000,
			'favorite' => true,
			'pinned' => false,
			'pageIds' => [$pageId1, $pageId2],
			'importSource' => 'hinote:test1234',
		]);

		$page1 = json_encode([
			'template' => 'todo',
			'width' => 1404,
			'height' => 1872,
			'bookmarked' => true,
			'strokes' => [
				[
					't' => 0,
					'w' => 3.5,
					'c' => -16777216, // Black ARGB
					'p' => [
						[100.5, 200.5, 1700000001000, 0.8],
						[120.0, 220.0, 1700000001050, 0.9],
						[150.0, 260.0, 1700000001100, 0.7],
					],
				],
			],
		]);

		$page2 = json_encode([
			'template' => 'lines_wide',
			'width' => 1404,
			'height' => 1872,
			'bookmarked' => false,
			'strokes' => [],
		]);

		$fakeJpeg = "\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00";

		$zipPath = $this->createTestZip([
			'manifest.json' => $manifest,
			'preview.jpg' => $fakeJpeg,
			"pages/{$pageId1}.json" => $page1,
			"pages/{$pageId2}.json" => $page2,
		]);

		try {
			$parsed = $this->parser->parseFile($zipPath);
			$this->assert($parsed['manifest']['id'] === $noteId, 'Valid Note: Manifest ID matches');
			$this->assert($parsed['manifest']['title'] === 'Test Notiz Äpfel', 'Valid Note: Title preserved with UTF-8');
			$this->assert(count($parsed['pages']) === 2, 'Valid Note: Two pages parsed');
			$this->assert($parsed['hasPreview'] === true, 'Valid Note: Preview detected');
			$this->assert(count($parsed['pages'][$pageId1]['strokes']) === 1, 'Valid Note: Page 1 strokes parsed');

			// Test PDF export
			$pdf = $this->pdfExport->exportNoteToPdf($parsed, 'de');
			$this->assert(str_starts_with($pdf, '%PDF-1.4'), 'PDF Export: Starts with valid header');
			$this->assert(str_ends_with(trim($pdf), '%%EOF'), 'PDF Export: Ends with %%EOF');
			$this->assert(str_contains($pdf, '/Count 2'), 'PDF Export: 2 pages recorded in catalog');
		} finally {
			@unlink($zipPath);
		}
	}

	private function testPathTraversalProhibition(): void {
		$zipPath = $this->createTestZip([
			'manifest.json' => '{"version":2,"id":"11111111-2222-3333-4444-555555555555","title":"t","folderPath":"","created":1,"modified":2,"favorite":false,"pinned":false,"pageIds":[]}',
			'../evil.txt' => 'evil content',
		]);

		try {
			$this->parser->parseFile($zipPath);
			$this->assert(false, 'Security: Prohibit relative path traversal (../evil.txt)', 'Should have thrown exception');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Prohibit relative path traversal (../evil.txt)');
		} finally {
			@unlink($zipPath);
		}

		$zipPath2 = $this->createTestZip([
			'manifest.json' => '{"version":2,"id":"11111111-2222-3333-4444-555555555555","title":"t","folderPath":"","created":1,"modified":2,"favorite":false,"pinned":false,"pageIds":[]}',
			'/etc/passwd' => 'root:x:0:0:...',
		]);

		try {
			$this->parser->parseFile($zipPath2);
			$this->assert(false, 'Security: Prohibit leading slash (/etc/passwd)', 'Should have thrown exception');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Prohibit leading slash (/etc/passwd)');
		} finally {
			@unlink($zipPath2);
		}

		$zipPath3 = $this->createTestZip([
			'manifest.json' => '{"version":2,"id":"11111111-2222-3333-4444-555555555555","title":"t","folderPath":"","created":1,"modified":2,"favorite":false,"pinned":false,"pageIds":[]}',
			'pages\\evil.json' => '{}',
		]);

		try {
			$this->parser->parseFile($zipPath3);
			$this->assert(false, 'Security: Prohibit backslashes in entry names', 'Should have thrown exception');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Prohibit backslashes in entry names');
		} finally {
			@unlink($zipPath3);
		}
	}

	private function testProhibitedEntryNames(): void {
		$zipPath = $this->createTestZip([
			'manifest.json' => '{"version":2,"id":"11111111-2222-3333-4444-555555555555","title":"t","folderPath":"","created":1,"modified":2,"favorite":false,"pinned":false,"pageIds":[]}',
			'script.php' => '<?php echo "evil"; ?>',
		]);

		try {
			$this->parser->parseFile($zipPath);
			$this->assert(false, 'Security: Prohibit arbitrary entries like script.php', 'Should have thrown exception');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Prohibit arbitrary entries like script.php');
		} finally {
			@unlink($zipPath);
		}
	}

	private function testMaxEntriesLimit(): void {
		$entries = [
			'manifest.json' => '{"version":2,"id":"11111111-2222-3333-4444-555555555555","title":"t","folderPath":"","created":1,"modified":2,"favorite":false,"pinned":false,"pageIds":[]}',
		];
		// Add 2005 page entries
		for ($i = 0; $i < 2005; $i++) {
			$uuid = sprintf('11111111-2222-3333-4444-%012d', $i);
			$entries["pages/{$uuid}.json"] = '{"template":"blank","width":10,"height":10,"strokes":[]}';
		}

		$zipPath = $this->createTestZip($entries);
		try {
			$this->parser->parseFile($zipPath);
			$this->assert(false, 'Security: Limit max entries in ZIP', 'Should have rejected > 2000 entries');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Limit max entries in ZIP');
		} finally {
			@unlink($zipPath);
		}
	}

	private function testMaxTotalBytesLimit(): void {
		$pageId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
		// Create large repeated content
		$largeJson = '{"template":"blank","width":10,"height":10,"strokes":[],"extra":"' . str_repeat('A', 1024 * 1024 * 17) . '"}';

		$zipPath = $this->createTestZip([
			'manifest.json' => "{\"version\":2,\"id\":\"11111111-2222-3333-4444-555555555555\",\"title\":\"t\",\"folderPath\":\"\",\"created\":1,\"modified\":2,\"favorite\":false,\"pinned\":false,\"pageIds\":[\"{$pageId}\"]}",
			"pages/{$pageId}.json" => $largeJson,
		]);

		try {
			$this->parser->parseFile($zipPath);
			$this->assert(false, 'Security: Limit entry size > 16MB', 'Should have rejected oversized entry');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Limit entry size > 16MB');
		} finally {
			@unlink($zipPath);
		}
	}

	private function testCorruptManifests(): void {
		$zipPath = $this->createTestZip([
			'manifest.json' => '{"version":999,"id":"11111111-2222-3333-4444-555555555555"}',
		]);

		try {
			$this->parser->parseFile($zipPath);
			$this->assert(false, 'Security: Reject future format version 999', 'Should have rejected unsupported version');
		} catch (PnoteParseException $e) {
			$this->assert(true, 'Security: Reject future format version 999');
		} finally {
			@unlink($zipPath);
		}
	}

	private function testAll21TemplatesExport(): void {
		$templates = [
			'blank', 'lines_wide', 'lines_narrow', 'dotted',
			'handwriting', 'millimeter', 'grid', 'letter', 'character_grid', 'staff',
			'todo', 'meeting', 'weekly', 'monthly', 'time_management',
			'tasks', 'error_log', 'reading_notes', 'cornell', 'point_sheet', 'swot',
		];

		$pageIds = [];
		$pages = [];
		foreach ($templates as $idx => $tmpl) {
			$pid = sprintf('00000000-0000-0000-0000-%012d', $idx + 1);
			$pageIds[] = $pid;
			$pages[$pid] = [
				'id' => $pid,
				'template' => $tmpl,
				'width' => 1404,
				'height' => 1872,
				'bookmarked' => ($idx % 3 === 0),
				'strokes' => [
					[
						't' => 1,
						'w' => 2.0,
						'c' => -65536, // Red
						'p' => [[150.0, 300.0, 1000, 0.5], [400.0, 600.0, 1050, 0.6]],
					],
				],
			];
		}

		$note = [
			'manifest' => [
				'version' => 2,
				'id' => '99999999-8888-7777-6666-555555555555',
				'title' => 'All Templates Test',
				'folderPath' => '',
				'created' => 1700000000000,
				'modified' => 1700000000000,
				'favorite' => false,
				'pinned' => false,
				'pageIds' => $pageIds,
			],
			'pages' => $pages,
		];

		$pdfDe = $this->pdfExport->exportNoteToPdf($note, 'de');
		$this->assert(strlen($pdfDe) > 1000, 'All 21 Templates: German PDF generation succeeded');
		$this->assert(str_contains($pdfDe, '/Count 21'), 'All 21 Templates: 21 pages present in PDF');

		$pdfEn = $this->pdfExport->exportNoteToPdf($note, 'en');
		$this->assert(strlen($pdfEn) > 1000, 'All 21 Templates: English PDF generation succeeded');
	}

	private function testPreviewExtraction(): void {
		$fakeJpeg = "\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00samplejpegdata";
		$zipPath = $this->createTestZip([
			'manifest.json' => '{"version":2,"id":"11111111-2222-3333-4444-555555555555","title":"t","folderPath":"","created":1,"modified":2,"favorite":false,"pinned":false,"pageIds":[]}',
			'preview.jpg' => $fakeJpeg,
		]);

		try {
			$preview = $this->parser->extractPreview($zipPath);
			$this->assert($preview === $fakeJpeg, 'Preview: Extracted preview.jpg faithfully');
		} finally {
			@unlink($zipPath);
		}
	}

	private const NOTE_ID = '11111111-2222-3333-4444-555555555555';
	private const PAGE_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
	private const IMG_FILE = '12345678-1234-1234-1234-123456789abc.png';
	private const AUDIO_FILE = '87654321-4321-4321-4321-cba987654321.m4a';

	private function manifestJson(): string {
		return (string)json_encode([
			'version' => 2, 'id' => self::NOTE_ID, 'title' => 'B1', 'folderPath' => '',
			'created' => 1, 'modified' => 2, 'favorite' => false, 'pinned' => false,
			'pageIds' => [self::PAGE_ID],
		]);
	}

	private function tinyPng(): string {
		$im = imagecreatetruecolor(8, 6);
		imagefill($im, 0, 0, (int)imagecolorallocate($im, 200, 30, 30));
		ob_start();
		imagepng($im);
		return (string)ob_get_clean();
	}

	/** @return array<string, mixed> */
	private function richPage(): array {
		return [
			'template' => 'blank', 'width' => 1404, 'height' => 1872, 'bookmarked' => false, 'strokes' => [],
			'textboxes' => [[
				'id' => 't1', 'text' => "Fett und kursiv\nPunkt eins\nPunkt zwei", 'x' => 100, 'y' => 200, 'w' => 600, 'h' => 300,
				'size' => 24, 'bgColor' => -2236963,
				'spans' => [[0, 0, 4, 0], [1, 9, 15, 0], [3, 0, 4, -65536], [2, 5, 8, 0], [4, 0, 4, 150]],
				'paras' => [[1, 0, 1, 0], [2, 0, 1, 1]],
			]],
			'images' => [['id' => 'i1', 'file' => self::IMG_FILE, 'x' => 100, 'y' => 600, 'w' => 400, 'h' => 300]],
			'audios' => [['id' => 'a1', 'file' => self::AUDIO_FILE, 'title' => 'Vortrag Äpfel', 'duration' => 65000]],
		];
	}

	private function richZip(): string {
		return $this->createTestZip([
			'manifest.json' => $this->manifestJson(),
			'pages/' . self::PAGE_ID . '.json' => (string)json_encode($this->richPage()),
			'media/' . self::IMG_FILE => $this->tinyPng(),
			'media/' . self::AUDIO_FILE => 'fake-m4a-bytes',
		]);
	}

	private function testRichPageContentParsing(): void {
		$zip = $this->richZip();
		try {
			$parsed = $this->parser->parseFile($zip);
			$page = $parsed['pages'][self::PAGE_ID];
			$this->assert(count($page['textboxes']) === 1 && $page['textboxes'][0]['size'] === 24.0, 'B1 Parse: Text box read');
			$this->assert(count($page['textboxes'][0]['spans']) === 5, 'B1 Parse: Spans read');
			$this->assert($page['textboxes'][0]['paras'] === [[1, 0, 1, 0], [2, 0, 1, 1]], 'B1 Parse: Paragraph formats read');
			$this->assert($page['textboxes'][0]['bgColor'] === -2236963, 'B1 Parse: Background colour read');
			$this->assert(count($page['images']) === 1 && $page['images'][0]['file'] === self::IMG_FILE, 'B1 Parse: Image read');
			$this->assert($page['audios'][0]['title'] === 'Vortrag Äpfel' && $page['audios'][0]['duration'] === 65000, 'B1 Parse: Audio name and duration read');
			$this->assert(!isset($parsed['media']), 'B1 Parse: Media not part of the browser JSON');
			$withMedia = $this->parser->parseFile($zip, false, true);
			$this->assert(isset($withMedia['media'][self::AUDIO_FILE]) && $withMedia['media'][self::AUDIO_FILE] === 'fake-m4a-bytes', 'B1 Parse: Media delivered on request');
		} finally {
			@unlink($zip);
		}
	}

	private function testPageContentLimitsAndSanitizing(): void {
		$page = [
			'textboxes' => [
				['text' => 'ok', 'x' => 0, 'y' => 0, 'w' => 100, 'h' => 50, 'size' => 20, 'spans' => [[9, 0, 1, 0], [0, 5, 3, 0], [0, 0, 99, 0], 'bad'], 'paras' => [[-1, 0, 0, 0], [0, 7, 9, 9]]],
				['text' => 'zero width', 'x' => 0, 'y' => 0, 'w' => 0, 'h' => 50],
				['text' => 'nan', 'x' => 'abc', 'y' => 0, 'w' => 100, 'h' => 50, 'size' => 5000],
			],
			'images' => [
				['file' => '../../etc/passwd', 'x' => 0, 'y' => 0, 'w' => 10, 'h' => 10],
				['file' => self::IMG_FILE, 'x' => 0, 'y' => 0, 'w' => -5, 'h' => 10],
				['file' => self::IMG_FILE, 'x' => 1, 'y' => 2, 'w' => 10, 'h' => 10],
			],
			'audios' => [
				['file' => 'evil.php', 'title' => 'x'],
				['file' => self::AUDIO_FILE, 'title' => str_repeat('x', 500), 'duration' => -3],
			],
		];
		$p = $this->parser->parsePage(self::PAGE_ID, (string)json_encode($page), 2);
		$this->assert(count($p['textboxes']) === 2, 'B1 Sanitize: Boxes without size are dropped');
		$this->assert($p['textboxes'][0]['spans'] === [[0, 0, 2, 0]], 'B1 Sanitize: Invalid spans dropped, ranges clamped');
		$this->assert($p['textboxes'][0]['paras'] === [[0, 2, 4, 4]], 'B1 Sanitize: Paragraph values clamped');
		$this->assert($p['textboxes'][1]['size'] === 1000.0 && $p['textboxes'][1]['x'] === 0.0, 'B1 Sanitize: Font size capped, non-numbers become 0');
		$this->assert(count($p['images']) === 1 && $p['images'][0]['x'] === 1.0, 'B1 Sanitize: Only valid media names and sizes kept');
		$this->assert(count($p['audios']) === 1 && mb_strlen($p['audios'][0]['title']) === 200 && $p['audios'][0]['duration'] === 0, 'B1 Sanitize: Audio title shortened, negative length 0');

		$many = ['textboxes' => array_fill(0, 5001, ['text' => 'a', 'x' => 0, 'y' => 0, 'w' => 10, 'h' => 10])];
		try {
			$this->parser->parsePage(self::PAGE_ID, (string)json_encode($many), 2);
			$this->assert(false, 'B1 Limit: Too many text boxes rejected', 'no exception');
		} catch (PnoteParseException) {
			$this->assert(true, 'B1 Limit: Too many text boxes rejected');
		}
		$long = ['textboxes' => [['text' => str_repeat('a', 200001), 'x' => 0, 'y' => 0, 'w' => 10, 'h' => 10]]];
		try {
			$this->parser->parsePage(self::PAGE_ID, (string)json_encode($long), 2);
			$this->assert(false, 'B1 Limit: Overlong text rejected', 'no exception');
		} catch (PnoteParseException) {
			$this->assert(true, 'B1 Limit: Overlong text rejected');
		}
	}

	private function testMediaExtraction(): void {
		$zip = $this->richZip();
		try {
			$this->assert($this->parser->extractMedia($zip, self::AUDIO_FILE) === 'fake-m4a-bytes', 'B1 Media: Extract existing file');
			$this->assert($this->parser->extractMedia($zip, '00000000-0000-0000-0000-000000000000.png') === null, 'B1 Media: Missing file gives null');
			$this->assert($this->parser->extractMedia($zip, '../manifest.json') === null, 'B1 Media: Path traversal name rejected');
			$this->assert($this->parser->extractMedia($zip, 'manifest.json') === null, 'B1 Media: Non-media entry rejected');
		} finally {
			@unlink($zip);
		}
	}

	/** @return array<string, mixed> */
	private function parsedRichNote(): array {
		$zip = $this->richZip();
		try {
			return $this->parser->parseFile($zip, false, true);
		} finally {
			@unlink($zip);
		}
	}

	private function testPdfWithTextImageAudio(): void {
		$note = $this->parsedRichNote();
		$pdf = $this->pdfExport->exportNoteToPdf($note, 'de');
		$this->assert(str_starts_with($pdf, '%PDF-1.4') && str_ends_with($pdf, "%%EOF\n"), 'B1 PDF: Valid framing');
		$this->assert(str_contains($pdf, '/Subtype /Image') && str_contains($pdf, '/DCTDecode'), 'B1 PDF: Image embedded as JPEG');
		$this->assert(str_contains($pdf, '/XObject'), 'B1 PDF: Image resource on page');
		$this->assert(str_contains($pdf, '/BaseFont /Helvetica-Bold') && str_contains($pdf, '/BaseFont /Helvetica-Oblique'), 'B1 PDF: Bold and italic fonts present');
		$this->assert(!str_contains($pdf, '/FileAttachment'), 'B1 PDF: No attachments without audio option');
		// The page content is Flate-compressed; check the decompressed stream for text and the audio name.
		preg_match_all('/stream\n(.*?)\nendstream/s', $pdf, $m);
		$all = '';
		foreach ($m[1] as $chunk) {
			$u = @gzuncompress($chunk);
			$all .= $u === false ? '' : $u;
		}
		$this->assert(str_contains($all, '(Fett) Tj') || str_contains($all, '(Fett'), 'B1 PDF: Text box text in content stream');
		$this->assert(!str_contains($all, 'Vortrag') && !str_contains($all, '1:05'), 'B1 PDF: Recordings are not drawn on the page');
		$this->assert(str_contains($all, ' Do'), 'B1 PDF: Image drawn');
	}

	private function testPdfAudioOption(): void {
		$note = $this->parsedRichNote();
		$pdf = $this->pdfExport->exportNoteToPdf($note, 'de', true);
		$this->assert(str_contains($pdf, '/Subtype /FileAttachment') && str_contains($pdf, '/Type /EmbeddedFile'), 'B1 PDF Audio: Attachment embedded');
		$this->assert(str_contains($pdf, 'fake-m4a-bytes'), 'B1 PDF Audio: Recording bytes included');
		$this->assert(str_contains($pdf, '/Subtype /audio#2Fmp4'), 'B1 PDF Audio: MIME type set');
		$this->assert(str_contains($pdf, '/Annots [') && str_contains($pdf, '/F (Vortrag _pfel.m4a)'), 'B1 PDF Audio: Attachment annotation on the page, named after the recording');

		unset($note['media']);
		$noMedia = $this->pdfExport->exportNoteToPdf($note, 'de', true);
		$this->assert(!str_contains($noMedia, '/FileAttachment') && str_starts_with($noMedia, '%PDF'), 'B1 PDF Audio: Missing media does not break the export');

		// External check with poppler when available and working: the file must render without errors.
		$tmp = tempnam(sys_get_temp_dir(), 'b1pdf_') . '.pdf';
		file_put_contents($tmp, $pdf);
		$ppm = trim((string)@shell_exec('command -v pdftoppm 2>/dev/null'));
		if ($ppm !== '') {
			exec(escapeshellarg($ppm) . ' -r 40 -png ' . escapeshellarg($tmp) . ' ' . escapeshellarg($tmp . '_out') . ' 2>&1', $lines, $code);
			if ($code === 134 || $code === 127 || $code === 126) {
				echo "  [SKIP] B1 PDF Audio: poppler not usable on this machine\n";
			} else {
				$this->assert($code === 0 && trim(implode('', $lines)) === '', 'B1 PDF Audio: poppler renders without errors', implode(' ', $lines));
			}
			foreach (glob($tmp . '_out*') ?: [] as $f) {
				@unlink($f);
			}
		}
		@unlink($tmp);
	}

	private function testTextRenderer(): void {
		$r = new \OCA\PaperNotes\Service\PdfTextRenderer();
		$this->assert(abs($r->textWidth('Hello', 10.0) - 22.78) < 0.01, 'B1 Text: Helvetica widths (Hello = 2278/1000 em)');
		$this->assert($r->textWidth('Hello', 10.0, true) > $r->textWidth('Hello', 10.0, false), 'B1 Text: Bold is wider');
		$this->assert($r->encodeChar(0xE4) === "\xE4" && $r->encodeChar(0x20AC) === "\x80" && $r->encodeChar(0x4E2D) === '?', 'B1 Text: WinAnsi encoding');
		$dense = [[0, 2, 0], [0, 2, 0], [0, 2, 1], [0, 2, 0]];
		$this->assert($r->marker($dense, 3) === '3.' && $r->marker($dense, 2) === '1.', 'B1 Text: Numbering follows level');
		$this->assert($this->parser->utf16Length("a\u{1F600}") === 3, 'B1 Text: UTF-16 length counts surrogate pairs');
		$box = ['x' => 0, 'y' => 0, 'w' => 80, 'h' => 40, 'size' => 20, 'bgColor' => 0, 'text' => str_repeat('Wort ', 200), 'spans' => [], 'paras' => []];
		$stream = $r->renderTextbox($box, 1.0, 100.0);
		$this->assert(str_contains($stream, 're W n') && substr_count($stream, 'BT') <= 4, 'B1 Text: Long text is clipped to the box');
	}
}

$runner = new PaperNotesTestRunner();
$success = $runner->run();
exit($success ? 0 : 1);
