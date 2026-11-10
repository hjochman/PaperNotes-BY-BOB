<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Service;

/**
 * Lays out and draws PaperNotes text boxes as PDF content stream commands.
 *
 * Uses the PDF standard fonts Helvetica (/F1), Helvetica-Bold (/F2), Helvetica-Oblique (/F3) and Helvetica-BoldOblique (/F4)
 * with their built-in character widths (Adobe AFM data), so no font file is needed. Text is encoded as WinAnsi (cp1252);
 * characters outside it are shown as "?". The layout follows the app: padding, indent unit of 1.5 times the font size,
 * list markers, paragraph alignment and the character formats bold, italic, underline, colour and relative size.
 */
class PdfTextRenderer {
	public const float PADDING = 4.0;
	public const float INDENT_EM = 1.5;
	private const float LINE_HEIGHT = 1.17;
	private const float ASCENT = 0.93;
	private const int WIDTH_FIRST = 32;

	// Span types as stored in the .pnote (SpanType.code)
	private const int SPAN_BOLD = 0;
	private const int SPAN_ITALIC = 1;
	private const int SPAN_UNDERLINE = 2;
	private const int SPAN_COLOR = 3;
	private const int SPAN_SIZE = 4;

	private const int LIST_BULLET = 1;
	private const int LIST_NUMBER = 2;
	private const int LIST_LETTER = 3;
	private const int LIST_SQUARE = 4;

	/** @var array<int, int> Helvetica widths (1/1000 em) for the WinAnsi codes 32..255 */
	private const array WIDTHS_REGULAR = [
		278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
		556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
		1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
		667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
		333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
		556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 556,
		556, 556, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 556, 611, 556,
		556, 222, 222, 333, 333, 350, 556, 1000, 333, 1000, 500, 333, 944, 556, 500, 667,
		278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
		400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611,
		667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
		722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
		556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,
		556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500,
	];

	/** @var array<int, int> Helvetica-Bold widths (1/1000 em) for the WinAnsi codes 32..255 */
	private const array WIDTHS_BOLD = [
		278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
		556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
		975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
		667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
		333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
		611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 556,
		556, 556, 278, 556, 500, 1000, 556, 556, 333, 1000, 667, 333, 1000, 556, 611, 556,
		556, 278, 278, 500, 500, 350, 556, 1000, 333, 1000, 556, 333, 944, 556, 500, 667,
		278, 333, 556, 556, 556, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,
		400, 584, 333, 333, 333, 611, 556, 278, 333, 333, 365, 556, 834, 834, 834, 611,
		722, 722, 722, 722, 722, 722, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
		722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
		556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278,
		611, 611, 611, 611, 611, 611, 611, 584, 611, 611, 611, 611, 611, 556, 611, 556,
	];

	/** @var array<int, string> */
	private array $byteCache = [];

	/**
	 * Encodes a Unicode code point as one WinAnsi byte ("?" if there is none).
	 */
	public function encodeChar(int $cp): string {
		if (isset($this->byteCache[$cp])) {
			return $this->byteCache[$cp];
		}
		if ($cp >= 32 && $cp < 127) {
			$byte = chr($cp);
		} elseif ($cp === 0xA0) {
			$byte = chr(32);
		} elseif ($cp >= 0xA1 && $cp <= 0xFF) {
			$byte = chr($cp);
		} else {
			$converted = @mb_convert_encoding(mb_chr($cp, 'UTF-8') ?: '?', 'Windows-1252', 'UTF-8');
			$byte = (is_string($converted) && strlen($converted) === 1 && ord($converted) >= 32) ? $converted : '?';
		}
		$this->byteCache[$cp] = $byte;
		return $byte;
	}

	/**
	 * Width of a WinAnsi byte in 1/1000 em.
	 */
	public function byteWidth(string $byte, bool $bold): int {
		$i = ord($byte) - self::WIDTH_FIRST;
		if ($i < 0) {
			$i = 0;
		}
		return ($bold ? self::WIDTHS_BOLD : self::WIDTHS_REGULAR)[$i] ?? 556;
	}

	/**
	 * Width of an encoded string in points.
	 */
	public function textWidth(string $encoded, float $size, bool $bold = false): float {
		$sum = 0;
		$n = strlen($encoded);
		for ($i = 0; $i < $n; $i++) {
			$sum += $this->byteWidth($encoded[$i], $bold);
		}
		return $sum * $size / 1000.0;
	}

	/**
	 * Marker of paragraph $i for list paragraphs ('' otherwise).
	 *
	 * @param array<int, array{0: int, 1: int, 2: int}> $dense [align, list, level] per paragraph
	 */
	public function marker(array $dense, int $i): string {
		$p = $dense[$i] ?? null;
		if ($p === null) {
			return '';
		}
		[, $list, $level] = $p;
		switch ($list) {
			case self::LIST_BULLET:
				return ($level % 2 === 0) ? "\u{2022}" : "\u{2013}";
			case self::LIST_SQUARE:
				return ($level % 2 === 0) ? "\u{25A0}" : "\u{25A1}";
			case self::LIST_NUMBER:
				return $this->listNumber($dense, $i) . '.';
			case self::LIST_LETTER:
				return chr(ord('a') + ($this->listNumber($dense, $i) - 1) % 26) . '.';
			default:
				return '';
		}
	}

	/**
	 * @param array<int, array{0: int, 1: int, 2: int}> $dense
	 */
	private function listNumber(array $dense, int $i): int {
		$me = $dense[$i];
		$n = 1;
		for ($k = $i - 1; $k >= 0; $k--) {
			$p = $dense[$k];
			if ($p[2] > $me[2]) {
				continue;
			}
			if ($p[2] < $me[2] || $p[1] !== $me[1]) {
				break;
			}
			$n++;
		}
		return $n;
	}

	/**
	 * Draws one text box (background and text) into PDF commands.
	 *
	 * @param array<string, mixed> $tb Parsed text box (x, y, w, h, size, bgColor, text, spans, paras) in page coordinates
	 * @param float $scale page coordinates -> points
	 * @param float $heightPt page height in points
	 */
	public function renderTextbox(array $tb, float $scale, float $heightPt): string {
		$x = (float)$tb['x'];
		$y = (float)$tb['y'];
		$w = (float)$tb['w'];
		$h = (float)$tb['h'];
		$fontSize = (float)$tb['size'];
		if ($w <= 0.0 || $h <= 0.0 || $fontSize <= 0.0) {
			return '';
		}

		$out = "q\n";
		$bg = (int)($tb['bgColor'] ?? 0);
		if ($bg !== 0) {
			$out .= sprintf("%s rg\n", $this->rgb($bg));
			$out .= sprintf("%.2f %.2f %.2f %.2f re f\n", $x * $scale, $heightPt - ($y + $h) * $scale, $w * $scale, $h * $scale);
		}

		$text = (string)($tb['text'] ?? '');
		if ($text === '') {
			return $out . "Q\n";
		}

		// Clip to the box
		$out .= sprintf("%.2f %.2f %.2f %.2f re W n\n", $x * $scale, $heightPt - ($y + $h) * $scale, $w * $scale, $h * $scale);

		$chars = $this->styledChars($text, $tb['spans'] ?? [], $fontSize);
		$paragraphs = [[]];
		foreach ($chars as $c) {
			if ($c['byte'] === "\n") {
				$paragraphs[] = [];
			} else {
				$paragraphs[count($paragraphs) - 1][] = $c;
			}
		}

		$dense = [];
		$paraMap = [];
		foreach (($tb['paras'] ?? []) as $pf) {
			$paraMap[(int)$pf[0]] = [(int)$pf[1], (int)$pf[2], (int)$pf[3]];
		}
		foreach (array_keys($paragraphs) as $idx) {
			$dense[$idx] = $paraMap[$idx] ?? [0, 0, 0];
		}

		$unit = $fontSize * self::INDENT_EM;
		$left = $x + self::PADDING;
		$innerW = $w - 2.0 * self::PADDING;
		$bottom = $y + $h;
		$top = $y + self::PADDING;

		foreach ($paragraphs as $idx => $para) {
			[$align, $list, $level] = $dense[$idx];
			$steps = $list !== 0 ? $level + 1 : $level;
			$indent = $steps * $unit;
			$avail = max(1.0, $innerW - $indent);
			$marker = $this->marker($dense, $idx);

			$lines = $this->wrap($para, $avail);
			foreach ($lines as $li => [$from, $to]) {
				$maxSize = $fontSize;
				for ($k = $from; $k < $to; $k++) {
					$maxSize = max($maxSize, $para[$k]['size']);
				}
				$baseline = $top + $maxSize * self::ASCENT;
				$lineH = $maxSize * self::LINE_HEIGHT;

				if ($top > $bottom) {
					break 2;
				}

				$lineW = 0.0;
				$trimTo = $to;
				while ($trimTo > $from && $para[$trimTo - 1]['byte'] === ' ') {
					$trimTo--;
				}
				for ($k = $from; $k < $trimTo; $k++) {
					$lineW += $para[$k]['w'];
				}
				if ($align === 1 && $list === 0) {
					$lx = $left + $indent + max(0.0, ($avail - $lineW) / 2.0);
				} elseif ($align === 2 && $list === 0) {
					$lx = $left + $innerW - $lineW;
				} else {
					$lx = $left + $indent;
				}

				if ($li === 0 && $marker !== '') {
					$out .= $this->drawMarker($marker, $left + $level * $unit, $baseline, $fontSize, $unit, $scale, $heightPt);
				}
				$out .= $this->drawLine($para, $from, $trimTo, $lx, $baseline, $scale, $heightPt);
				$top += $lineH;
			}
			if (empty($lines)) {
				$top += $fontSize * self::LINE_HEIGHT;
			}
		}

		return $out . "Q\n";
	}

	/**
	 * Characters with their formats resolved from the spans (ranges in UTF-16 code units).
	 *
	 * @param array<int, array{0: int, 1: int, 2: int, 3: int}> $spans
	 * @return array<int, array{byte: string, w: float, size: float, bold: bool, italic: bool, underline: bool, color: int}>
	 */
	private function styledChars(string $text, array $spans, float $fontSize): array {
		$glyphs = mb_str_split($text, 1, 'UTF-8');
		$total = 0;
		$offsets = [];
		foreach ($glyphs as $g) {
			$offsets[] = $total;
			$total += (mb_ord($g, 'UTF-8') > 0xFFFF) ? 2 : 1;
		}

		$bold = [];
		$italic = [];
		$underline = [];
		$color = [];
		$size = [];
		foreach ($spans as $sp) {
			[$type, $start, $end, $value] = $sp;
			for ($u = max(0, (int)$start); $u < min($total, (int)$end); $u++) {
				switch ($type) {
					case self::SPAN_BOLD:
						$bold[$u] = true;
						break;
					case self::SPAN_ITALIC:
						$italic[$u] = true;
						break;
					case self::SPAN_UNDERLINE:
						$underline[$u] = true;
						break;
					case self::SPAN_COLOR:
						$color[$u] = (int)$value;
						break;
					case self::SPAN_SIZE:
						$size[$u] = max(1, (int)$value);
						break;
				}
			}
		}

		$out = [];
		foreach ($glyphs as $i => $g) {
			$o = $offsets[$i];
			$cp = mb_ord($g, 'UTF-8');
			$byte = $cp === 10 ? "\n" : $this->encodeChar($cp);
			$isBold = isset($bold[$o]);
			$charSize = $fontSize * (($size[$o] ?? 100) / 100.0);
			$out[] = [
				'byte' => $byte,
				'w' => $byte === "\n" ? 0.0 : $this->byteWidth($byte, $isBold) * $charSize / 1000.0,
				'size' => $charSize,
				'bold' => $isBold,
				'italic' => isset($italic[$o]),
				'underline' => isset($underline[$o]),
				'color' => $color[$o] ?? (int)0xFF000000,
			];
		}
		return $out;
	}

	/**
	 * Greedy word wrap of one paragraph; returns [from, to) index ranges of the lines (at least one).
	 *
	 * @param array<int, array{byte: string, w: float}> $para
	 * @return array<int, array{0: int, 1: int}>
	 */
	private function wrap(array $para, float $avail): array {
		$n = count($para);
		if ($n === 0) {
			return [];
		}
		$lines = [];
		$start = 0;
		$w = 0.0;
		$breakAt = -1;
		for ($i = 0; $i < $n; $i++) {
			$cw = $para[$i]['w'];
			$isSpace = $para[$i]['byte'] === ' ';
			if (!$isSpace && $w + $cw > $avail && $i > $start) {
				if ($breakAt > $start) {
					$lines[] = [$start, $breakAt];
					$start = $breakAt;
				} else {
					$lines[] = [$start, $i];
					$start = $i;
				}
				$w = 0.0;
				for ($k = $start; $k < $i; $k++) {
					$w += $para[$k]['w'];
				}
				$breakAt = -1;
			}
			$w += $cw;
			if ($isSpace) {
				$breakAt = $i + 1;
			}
		}
		$lines[] = [$start, $n];
		return $lines;
	}

	/**
	 * Draws the characters [from, to) of a paragraph as runs of equal format starting at ($x, $baseline).
	 *
	 * @param array<int, array<string, mixed>> $para
	 */
	private function drawLine(array $para, int $from, int $to, float $x, float $baseline, float $scale, float $heightPt): string {
		$out = '';
		$i = $from;
		while ($i < $to) {
			$c = $para[$i];
			$j = $i;
			$run = '';
			$runW = 0.0;
			while ($j < $to
				&& $para[$j]['size'] === $c['size']
				&& $para[$j]['bold'] === $c['bold']
				&& $para[$j]['italic'] === $c['italic']
				&& $para[$j]['underline'] === $c['underline']
				&& $para[$j]['color'] === $c['color']) {
				$run .= $para[$j]['byte'];
				$runW += $para[$j]['w'];
				$j++;
			}
			$font = $c['bold'] ? ($c['italic'] ? 4 : 2) : ($c['italic'] ? 3 : 1);
			$px = $x * $scale;
			$py = $heightPt - $baseline * $scale;
			$rgb = $this->rgb($c['color']);
			$escaped = strtr($run, ['\\' => '\\\\', '(' => '\\(', ')' => '\\)', "\r" => ' ']);
			if (trim($run) !== '' || $c['underline']) {
				$out .= sprintf("BT /F%d %.2f Tf %s rg %.2f %.2f Td (%s) Tj ET\n", $font, $c['size'] * $scale, $rgb, $px, $py, $escaped);
			}
			if ($c['underline'] && $runW > 0.0) {
				$uy = $heightPt - ($baseline + $c['size'] * 0.12) * $scale;
				$out .= sprintf("%s RG %.2f w %.2f %.2f m %.2f %.2f l S\n", $rgb, max(0.25, $c['size'] * 0.06 * $scale), $px, $uy, ($x + $runW) * $scale, $uy);
			}
			$x += $runW;
			$i = $j;
		}
		return $out;
	}

	/**
	 * Draws a list marker: dot and squares as shapes, numbers, letters and dashes as text.
	 */
	private function drawMarker(string $marker, float $left, float $baseline, float $size, float $unit, float $scale, float $heightPt): string {
		$black = '0 0 0';
		if ($marker === "\u{2022}" || $marker === "\u{25A0}" || $marker === "\u{25A1}") {
			$cx = ($left + $unit * 0.3) * $scale;
			$cy = $heightPt - ($baseline - $size * 0.4) * $scale;
			if ($marker === "\u{2022}") {
				$r = $size * 0.17 * $scale;
				$k = $r * 0.55228475;
				return sprintf(
					"%s rg %.2f %.2f m %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c f\n",
					$black,
					$cx + $r, $cy,
					$cx + $r, $cy + $k, $cx + $k, $cy + $r, $cx, $cy + $r,
					$cx - $k, $cy + $r, $cx - $r, $cy + $k, $cx - $r, $cy,
					$cx - $r, $cy - $k, $cx - $k, $cy - $r, $cx, $cy - $r,
					$cx + $k, $cy - $r, $cx + $r, $cy - $k, $cx + $r, $cy
				);
			}
			$half = $size * 0.178 * $scale;
			if ($marker === "\u{25A0}") {
				return sprintf("%s rg %.2f %.2f %.2f %.2f re f\n", $black, $cx - $half, $cy - $half, 2 * $half, 2 * $half);
			}
			return sprintf("%s RG %.2f w %.2f %.2f %.2f %.2f re S\n", $black, max(0.25, $size * 0.06 * $scale), $cx - $half, $cy - $half, 2 * $half, 2 * $half);
		}
		$encoded = '';
		foreach (mb_str_split($marker, 1, 'UTF-8') as $g) {
			$encoded .= $this->encodeChar(mb_ord($g, 'UTF-8'));
		}
		return sprintf(
			"BT /F1 %.2f Tf %s rg %.2f %.2f Td (%s) Tj ET\n",
			$size * $scale,
			$black,
			$left * $scale,
			$heightPt - $baseline * $scale,
			strtr($encoded, ['\\' => '\\\\', '(' => '\\(', ')' => '\\)'])
		);
	}

	/**
	 * PDF "r g b" components of an ARGB colour (the alpha byte is ignored).
	 */
	private function rgb(int $argb): string {
		return sprintf('%.3f %.3f %.3f', (($argb >> 16) & 0xFF) / 255.0, (($argb >> 8) & 0xFF) / 255.0, ($argb & 0xFF) / 255.0);
	}
}
