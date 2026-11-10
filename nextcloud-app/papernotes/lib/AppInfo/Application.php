<?php

declare(strict_types=1);

namespace OCA\PaperNotes\AppInfo;

use OCA\Files\Event\LoadAdditionalScriptsEvent;
use OCA\PaperNotes\Listener\LoadScriptsListener;
use OCA\PaperNotes\Listener\SabreListener;
use OCA\Viewer\Event\LoadViewer;
use OCP\AppFramework\App;
use OCP\AppFramework\Bootstrap\IBootContext;
use OCP\AppFramework\Bootstrap\IBootstrap;
use OCP\AppFramework\Bootstrap\IRegistrationContext;
use OCP\Files\IMimeTypeDetector;
use OCP\SabrePluginEvent;

/**
 * Main application bootstrap for PaperNotes Nextcloud App.
 */
class Application extends App implements IBootstrap {
	public const string APP_ID = 'papernotes';

	public function __construct() {
		parent::__construct(self::APP_ID);
	}

	#[\Override]
	public function register(IRegistrationContext $context): void {
		$context->registerEventListener(LoadViewer::class, LoadScriptsListener::class);
		$context->registerEventListener(LoadAdditionalScriptsEvent::class, LoadScriptsListener::class);
		$context->registerEventListener(SabrePluginEvent::class, SabreListener::class);
	}

	#[\Override]
	public function boot(IBootContext $context): void {
		$context->injectFn(function(IMimeTypeDetector $detector) {
			$detector->registerType('pnote', 'application/x-papernotes');
		});
	}
}
