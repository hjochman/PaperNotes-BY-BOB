<?php

declare(strict_types=1);

namespace OCA\PaperNotes\Service;

use Exception;
use OCP\Files\File;
use OCP\Files\IRootFolder;
use OCP\Files\NotFoundException;
use OCP\Files\NotPermittedException;
use OCP\IUser;
use OCP\IUserSession;

/**
 * Service to securely access and resolve .pnote files in the file tree of the signed-in user.
 */
class PnoteStorageService {
	public function __construct(
		private IRootFolder $rootFolder,
		private IUserSession $userSession,
	) {
	}

	/**
	 * Resolves a .pnote file by its Nextcloud numeric file ID with permission checks.
	 *
	 * Only files in the file tree of the signed-in user are found. That includes files shared with the user
	 * (user, group, team, Talk and Deck shares are mounted into the user's file tree once accepted), but not
	 * public link shares opened without signing in, and not shares the user has not accepted yet.
	 *
	 * @param int $fileId
	 * @return File
	 * @throws NotFoundException
	 * @throws NotPermittedException
	 * @throws Exception
	 */
	public function getFileById(int $fileId): File {
		$user = $this->userSession->getUser();
		if ($user instanceof IUser) {
			$userFolder = $this->rootFolder->getUserFolder($user->getUID());
			$nodes = $userFolder->getById($fileId);
			if (!empty($nodes)) {
				$node = $nodes[0];
				if ($node instanceof File) {
					$this->validatePnoteFile($node);
					return $node;
				}
			}
		}

		throw new NotFoundException('Note file not found or inaccessible');
	}

	/**
		* Lists all readable .pnote files in a folder by folder ID or relative path.
		*
		* @param int|null $folderId
		* @param string|null $dir
		* @return array<File>
		*/
	public function getNotesInFolder(?int $folderId = null, ?string $dir = null): array {
		$user = $this->userSession->getUser();
		if (!$user instanceof IUser) {
			return [];
		}

		$userFolder = $this->rootFolder->getUserFolder($user->getUID());
		$targetFolder = null;

		if ($folderId !== null && $folderId > 0) {
			$nodes = $userFolder->getById($folderId);
			if (!empty($nodes) && $nodes[0] instanceof \OCP\Files\Folder) {
				$targetFolder = $nodes[0];
			}
		}

		if ($targetFolder === null && $dir !== null) {
			$cleanDir = trim($dir, '/');
			try {
				$node = $cleanDir === '' ? $userFolder : $userFolder->get($cleanDir);
				if ($node instanceof \OCP\Files\Folder) {
					$targetFolder = $node;
				}
			} catch (\Throwable) {}
		}

		if ($targetFolder === null) {
			return [];
		}

		$files = [];
		try {
			foreach ($targetFolder->getDirectoryListing() as $child) {
				if ($child instanceof File && str_ends_with(strtolower($child->getName()), '.pnote') && $child->isReadable()) {
					$files[] = $child;
				}
			}
		} catch (\Throwable) {}

		return $files;
	}

	/**
		* Validates that the file has a .pnote extension and is readable.
	 *
	 * @param File $file
	 * @throws NotPermittedException
	 * @throws Exception
	 */
	private function validatePnoteFile(File $file): void {
		if (!$file->isReadable()) {
			throw new NotPermittedException('Read permission denied for note file');
		}

		$name = strtolower($file->getName());
		if (!str_ends_with($name, '.pnote')) {
			throw new Exception('File is not a .pnote document');
		}
	}
}
