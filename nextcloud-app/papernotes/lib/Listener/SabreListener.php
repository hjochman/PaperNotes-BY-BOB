<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Listener;

use OCA\PaperNotes\Connector\Sabre\SabrePlugin;
use OCA\PaperNotes\Service\PnoteParserService;
use OCP\EventDispatcher\Event;
use OCP\EventDispatcher\IEventListener;
use OCP\SabrePluginEvent;

/**
 * Listener that attaches PaperNotes SabreDAV plugin to Nextcloud WebDAV server.
 *
 * @template-implements IEventListener<SabrePluginEvent>
 */
class SabreListener implements IEventListener {
	public function __construct(
		private PnoteParserService $parserService,
	) {
	}

	#[\Override]
	public function handle(Event $event): void {
		if (!$event instanceof SabrePluginEvent) {
			return;
		}

		$server = $event->getServer();
		$server->addPlugin(new SabrePlugin($this->parserService));
	}
}
