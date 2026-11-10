<?php

declare(strict_types=1);

return [
	'routes' => [
		['name' => 'note#getTitles', 'url' => '/api/titles', 'verb' => 'GET'],
		['name' => 'note#getNoteData', 'url' => '/api/notes/{fileId}', 'verb' => 'GET'],
		['name' => 'note#getPreview', 'url' => '/api/notes/{fileId}/preview', 'verb' => 'GET'],
		['name' => 'note#getMedia', 'url' => '/api/notes/{fileId}/media/{name}', 'verb' => 'GET', 'requirements' => ['name' => '[0-9a-f\\-]{36}\\.[a-z0-9]{1,8}']],
		['name' => 'note#exportPdf', 'url' => '/api/notes/{fileId}/pdf', 'verb' => 'GET'],
		['name' => 'viewer#show', 'url' => '/view/{fileId}', 'verb' => 'GET'],
	],
];
