<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Connector\Sabre;

use OCA\DAV\Connector\Sabre\File as SabreFile;
use OCA\PaperNotes\Service\PnoteParserService;
use Sabre\DAV\INode;
use Sabre\DAV\PropFind;
use Sabre\DAV\Server;
use Sabre\DAV\ServerPlugin;

/**
 * SabreDAV plugin that provides human-readable note titles as {DAV:}displayname for .pnote files.
 */
class SabrePlugin extends ServerPlugin {
	/** @var array<int, string> */
	private static array $titleCache = [];

	public function __construct(
		private PnoteParserService $parserService,
	) {
	}

	#[\Override]
	public function initialize(Server $server): void {
		$server->on('propFind', [$this, 'propFind'], 20);
	}

	public function propFind(PropFind $propFind, INode $node): void {
		if (!($node instanceof SabreFile)) {
			return;
		}

		$fileInfo = $node->getFileInfo();
		if ($fileInfo === null) {
			return;
		}

		$name = strtolower($fileInfo->getName());
		if (!str_ends_with($name, '.pnote')) {
			return;
		}

		$fileId = $fileInfo->getId();

		$propFind->handle('{DAV:}displayname', function() use ($node, $fileId, $fileInfo) {
			if (isset(self::$titleCache[$fileId])) {
				return self::$titleCache[$fileId];
			}

			try {
				$stream = $node->get();
				if (is_resource($stream)) {
					$content = stream_get_contents($stream);
					fclose($stream);
				} else {
					$content = (string)$stream;
				}

				if ($content !== '') {
					$parsed = $this->parserService->parseString($content, true);
					$title = trim((string)($parsed['manifest']['title'] ?? ''));
					if ($title !== '') {
						$displayName = $title . '.pnote';
						self::$titleCache[$fileId] = $displayName;
						return $displayName;
					}
				}
			} catch (\Throwable) {}

			$fallback = $fileInfo->getName();
			self::$titleCache[$fileId] = $fallback;
			return $fallback;
		});
	}
}
