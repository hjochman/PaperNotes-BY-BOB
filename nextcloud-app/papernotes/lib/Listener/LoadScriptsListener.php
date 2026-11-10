<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Listener;

use OCA\Files\Event\LoadAdditionalScriptsEvent;
use OCA\PaperNotes\AppInfo\Application;
use OCA\Viewer\Event\LoadViewer;
use OCP\EventDispatcher\Event;
use OCP\EventDispatcher\IEventListener;
use OCP\Util;

/**
 * Event listener that injects PaperNotes scripts and styles into Files and Viewer views.
 *
 * @template-implements IEventListener<Event>
 */
class LoadScriptsListener implements IEventListener {
	#[\Override]
	public function handle(Event $event): void {
		if (!($event instanceof LoadViewer || $event instanceof LoadAdditionalScriptsEvent)) {
			return;
		}

		Util::addStyle(Application::APP_ID, 'papernotes');
		Util::addInitScript(Application::APP_ID, 'papernotes-viewer');
		Util::addInitScript(Application::APP_ID, 'papernotes-main');
		Util::addScript(Application::APP_ID, 'papernotes-viewer');
		Util::addScript(Application::APP_ID, 'papernotes-main');
	}
}
