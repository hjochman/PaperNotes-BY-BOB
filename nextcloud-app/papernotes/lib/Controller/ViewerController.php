<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Controller;

use OCP\AppFramework\Controller;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\TemplateResponse;
use OCP\IL10N;
use OCP\IRequest;
use OCP\Util;

/**
 * Controller for the standalone web viewer.
 */
class ViewerController extends Controller {
	public function __construct(
		string $appName,
		IRequest $request,
		private IL10N $l10n,
	) {
		parent::__construct($appName, $request);
	}

	/**
	 * Full page standalone view for a .pnote notebook.
	 *
	 * @NoCSRFRequired
	 * @NoAdminRequired
	 * @param int $fileId
	 * @return TemplateResponse
	 */
	#[NoCSRFRequired]
	#[NoAdminRequired]
	public function show(int $fileId): TemplateResponse {
		Util::addScript($this->appName, 'papernotes-viewer');
		Util::addScript($this->appName, 'papernotes-main');
		Util::addStyle($this->appName, 'papernotes');

		return new TemplateResponse($this->appName, 'viewer', [
			'fileId' => $fileId,
			'lang' => $this->l10n->getLanguageCode(),
		]);
	}
}
