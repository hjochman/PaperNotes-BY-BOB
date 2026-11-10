/**
 * PaperNotes File Action and Viewer Registration for Nextcloud.
 * Compatible with Nextcloud 28–35+ (@nextcloud/files scoped globals & OCA.Viewer).
 */
(function(window, document) {
    'use strict';

    const ICON_OPEN_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg>';
    const ICON_DOWNLOAD_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>';

    function l10n(key, fallback) {
        if (typeof window.t === 'function') {
            try {
                const translated = window.t('papernotes', fallback);
                if (translated) return translated;
            } catch (e) {}
        }
        return fallback;
    }

    function isPaperNotesNode(node) {
        if (!node) return false;
        const ext = (node.extension || '').toLowerCase();
        const source = (node.source || '').toLowerCase();
        const name = (node.displayname || node.basename || node.name || '').toLowerCase();
        const mime = (node.mime || '').toLowerCase();

        return ext === '.pnote' ||
               ext === 'pnote' ||
               source.endsWith('.pnote') ||
               name.endsWith('.pnote') ||
               mime === 'application/x-papernotes' ||
               mime === 'application/x-pnote';
    }

    function isPaperNotesRow(row) {
        if (!row) return false;
        const extEl = row.querySelector('.files-list__row-name-ext');
        if (extEl && extEl.textContent.trim().toLowerCase() === '.pnote') {
            return true;
        }
        const nameAttr = (row.getAttribute('data-cy-files-list-row-name') || row.getAttribute('data-file') || '').toLowerCase();
        if (nameAttr.endsWith('.pnote')) {
            return true;
        }
        const rowText = (row.textContent || '').toLowerCase();
        return rowText.includes('.pnote');
    }

    function openNote(fileId, filename) {
        if (window.PaperNotes && window.PaperNotes.viewerInstance) {
            window.PaperNotes.viewerInstance.open(fileId, null);
        } else {
            console.error('[PaperNotes] Viewer instance not initialized yet');
        }
    }

    function downloadPdf(fileId) {
        let url = (window.OC && window.OC.generateUrl)
            ? window.OC.generateUrl('/apps/papernotes/api/notes/' + encodeURIComponent(fileId) + '/pdf')
            : ('/apps/papernotes/api/notes/' + encodeURIComponent(fileId) + '/pdf');
        if (window.OC && window.OC.requestToken) {
            url += (url.includes('?') ? '&' : '?') + 'requesttoken=' + encodeURIComponent(window.OC.requestToken);
        }
        window.open(url, '_blank');
    }

    /**
     * Modern Nextcloud (@nextcloud/files) File Actions registration.
     */
    function registerModernFileActions() {
        if (!window._nc_files_scope) return;

        const openAction = {
            id: 'papernotes-open',
            displayName: () => l10n('open', 'In PaperNotes öffnen'),
            iconSvgInline: () => ICON_OPEN_SVG,
            order: 0,
            default: 'default',
            enabled: (context) => {
                const nodes = (context && context.nodes) ? context.nodes : (Array.isArray(context) ? context : [context]);
                if (!nodes || nodes.length !== 1) return false;
                return isPaperNotesNode(nodes[0]);
            },
            exec: (context) => {
                const nodes = (context && context.nodes) ? context.nodes : (Array.isArray(context) ? context : [context]);
                const node = nodes && nodes[0] ? nodes[0] : null;
                if (!node) return;
                const fileId = node.fileid || node.id || (node.attributes && node.attributes.fileid);
                openNote(fileId, node.displayname || node.basename || node.name);
            }
        };

        const downloadAction = {
            id: 'papernotes-download-pdf',
            displayName: () => l10n('download_pdf', 'Als PDF herunterladen'),
            iconSvgInline: () => ICON_DOWNLOAD_SVG,
            order: 10,
            enabled: (context) => {
                const nodes = (context && context.nodes) ? context.nodes : (Array.isArray(context) ? context : [context]);
                if (!nodes || nodes.length !== 1) return false;
                return isPaperNotesNode(nodes[0]);
            },
            exec: (context) => {
                const nodes = (context && context.nodes) ? context.nodes : (Array.isArray(context) ? context : [context]);
                const node = nodes && nodes[0] ? nodes[0] : null;
                if (!node) return;
                const fileId = node.fileid || node.id || (node.attributes && node.attributes.fileid);
                downloadPdf(fileId);
            }
        };

        Object.keys(window._nc_files_scope).forEach(versionKey => {
            const scope = window._nc_files_scope[versionKey];
            if (!scope || typeof scope !== 'object') return;

            scope.fileActions = scope.fileActions || new Map();

            [openAction, downloadAction].forEach(action => {
                if (!scope.fileActions.has(action.id)) {
                    scope.fileActions.set(action.id, action);
                    if (scope.registry && typeof scope.registry.dispatchTypedEvent === 'function') {
                        try {
                            scope.registry.dispatchTypedEvent('register:action', new CustomEvent('register:action', { detail: action }));
                        } catch (e) {
                            console.warn('[PaperNotes] dispatchTypedEvent error:', e);
                        }
                    }
                }
            });
        });
    }

    /**
     * Classic Nextcloud (OCA.Files.fileActions) registration.
     */
    function registerClassicFileActions() {
        if (!window.OCA || !window.OCA.Files || !window.OCA.Files.fileActions) return;

        const fileActions = window.OCA.Files.fileActions;
        const mimes = ['application/x-papernotes', 'application/x-pnote', 'application/octet-stream', 'application/zip'];

        mimes.forEach(mime => {
            fileActions.registerAction({
                name: 'PaperNotesOpen',
                displayName: l10n('open', 'In PaperNotes öffnen'),
                mime: mime,
                permissions: (window.OC && window.OC.PERMISSION_READ) || 1,
                iconClass: 'icon-edit',
                condition: (context) => {
                    const name = context.name || (context.fileInfoModel ? context.fileInfoModel.get('name') : '');
                    return isPaperNotesNode({ basename: name, mime: mime });
                },
                actionHandler: function(filename, context) {
                    const fileId = context.fileInfoModel ? context.fileInfoModel.get('id') : (context.id || context.fileId);
                    openNote(fileId, filename);
                }
            });

            fileActions.registerAction({
                name: 'PaperNotesDownloadPdf',
                displayName: l10n('download_pdf', 'Als PDF herunterladen'),
                mime: mime,
                permissions: (window.OC && window.OC.PERMISSION_READ) || 1,
                iconClass: 'icon-download',
                condition: (context) => {
                    const name = context.name || (context.fileInfoModel ? context.fileInfoModel.get('name') : '');
                    return isPaperNotesNode({ basename: name, mime: mime });
                },
                actionHandler: function(filename, context) {
                    const fileId = context.fileInfoModel ? context.fileInfoModel.get('id') : (context.id || context.fileId);
                    downloadPdf(fileId);
                }
            });
        });

        fileActions.setDefault('application/x-papernotes', 'PaperNotesOpen');
        fileActions.setDefault('application/x-pnote', 'PaperNotesOpen');
    }

    /**
     * Nextcloud Viewer handler registration.
     */
    function registerViewerHandler() {
        const handler = {
            id: 'papernotes',
            mimes: ['application/x-papernotes', 'application/x-pnote'],
            open: function(file) {
                const fileId = file.id || file.fileId;
                openNote(fileId, file.name || file.basename);
            }
        };

        if (window.OCA && window.OCA.Viewer && typeof window.OCA.Viewer.registerHandler === 'function') {
            try {
                window.OCA.Viewer.registerHandler(handler);
            } catch (e) {}
        } else {
            window._oca_viewer_handlers = window._oca_viewer_handlers || [];
            window._oca_viewer_handlers.push(handler);
        }
    }

    /**
     * Title Enrichment: Replaces UUID filenames in Nextcloud file list with human-readable note titles.
     */
    const titleCache = new Map();
    let pendingFetchIds = new Set();
    let lastFetchedDir = null;
    let fetchTimeout = null;

    function getStoreNodes() {
        const list = [];
        if (window._nc_files_pinia) {
            try {
                const filesStore = window._nc_files_pinia._s ? window._nc_files_pinia._s.get('files') : null;
                if (filesStore && filesStore.files) {
                    Object.values(filesStore.files).forEach(n => { if (n) list.push(n); });
                } else if (window._nc_files_pinia.state && window._nc_files_pinia.state.value && window._nc_files_pinia.state.value.files) {
                    const raw = window._nc_files_pinia.state.value.files.files || window._nc_files_pinia.state.value.files;
                    Object.values(raw).forEach(n => { if (n) list.push(n); });
                }
            } catch (e) {}
        }
        return list;
    }

    function fetchFolderTitles(dirPath) {
        if (!dirPath || dirPath === lastFetchedDir) return;
        lastFetchedDir = dirPath;

        const url = (window.OC && window.OC.generateUrl)
            ? window.OC.generateUrl('/apps/papernotes/api/titles?dir=' + encodeURIComponent(dirPath))
            : ('/apps/papernotes/api/titles?dir=' + encodeURIComponent(dirPath));

        const headers = {};
        if (window.OC && window.OC.requestToken) {
            headers['requesttoken'] = window.OC.requestToken;
        }

        fetch(url, { headers, credentials: 'same-origin' })
            .then(res => res.ok ? res.json() : null)
            .then(json => {
                if (json && json.status === 'success' && json.titles) {
                    Object.keys(json.titles).forEach(key => {
                        const meta = json.titles[key];
                        titleCache.set(key, meta);
                        if (meta.id) {
                            titleCache.set(parseInt(meta.id, 10), meta);
                            titleCache.set(String(meta.id), meta);
                        }
                        if (meta.name) {
                            titleCache.set(meta.name, meta);
                            const base = meta.name.replace(/\.pnote$/i, '');
                            titleCache.set(base, meta);
                        }
                    });
                    applyCachedTitles();
                }
            })
            .catch(() => {});
    }

    function queueFetchTitles(fileIds) {
        fileIds.forEach(id => {
            if (id && !titleCache.has(id) && !titleCache.has(parseInt(id, 10))) {
                pendingFetchIds.add(id);
            }
        });

        if (pendingFetchIds.size === 0) {
            applyCachedTitles();
            return;
        }

        if (fetchTimeout) {
            clearTimeout(fetchTimeout);
        }

        fetchTimeout = setTimeout(flushPendingTitleFetches, 50);
    }

    function flushPendingTitleFetches() {
        if (pendingFetchIds.size === 0) return;

        const idsToFetch = Array.from(pendingFetchIds).slice(0, 100);
        idsToFetch.forEach(id => pendingFetchIds.delete(id));

        const url = (window.OC && window.OC.generateUrl)
            ? window.OC.generateUrl('/apps/papernotes/api/titles?ids=' + idsToFetch.join(','))
            : ('/apps/papernotes/api/titles?ids=' + idsToFetch.join(','));

        const headers = {};
        if (window.OC && window.OC.requestToken) {
            headers['requesttoken'] = window.OC.requestToken;
        }

        fetch(url, { headers, credentials: 'same-origin' })
            .then(res => res.ok ? res.json() : null)
            .then(json => {
                if (json && json.status === 'success' && json.titles) {
                    Object.keys(json.titles).forEach(key => {
                        const meta = json.titles[key];
                        titleCache.set(key, meta);
                        if (meta.id) {
                            titleCache.set(parseInt(meta.id, 10), meta);
                            titleCache.set(String(meta.id), meta);
                        }
                        if (meta.name) {
                            titleCache.set(meta.name, meta);
                            const base = meta.name.replace(/\.pnote$/i, '');
                            titleCache.set(base, meta);
                        }
                    });
                    applyCachedTitles();
                }
            })
            .catch(() => {});
    }

    function findMetadata(fileId, fileName, basename) {
        if (fileId && (titleCache.has(fileId) || titleCache.has(parseInt(fileId, 10)))) {
            return titleCache.get(fileId) || titleCache.get(parseInt(fileId, 10));
        }
        if (fileName && titleCache.has(fileName)) {
            return titleCache.get(fileName);
        }
        if (basename && titleCache.has(basename)) {
            return titleCache.get(basename);
        }
        return null;
    }

    function applyCachedTitles() {
        // 1. Update Pinia Store nodes for reactive Vue rendering
        const storeNodes = getStoreNodes();
        storeNodes.forEach(node => {
            if (isPaperNotesNode(node)) {
                const id = node.fileid || node.id;
                const name = node.basename || node.displayname || node.name || '';
                const base = name.replace(/\.pnote$/i, '');
                const meta = findMetadata(id, name, base);
                if (meta && meta.title) {
                    if (node.displayname !== meta.title) {
                        node.displayname = meta.title;
                    }
                }
            }
        });

        // 2. Direct DOM enrichment for table rows and grid cards
        const rows = document.querySelectorAll('tr.files-list__row, tr[data-file], .files-list__grid-item, div.files-list__row');
        rows.forEach(row => {
            if (!isPaperNotesRow(row)) return;

            const fileId = row.getAttribute('data-cy-files-list-row-fileid') ||
                           row.getAttribute('data-id') ||
                           row.getAttribute('data-fileid');
            const fileNameAttr = row.getAttribute('data-cy-files-list-row-name') ||
                                 row.getAttribute('data-file') ||
                                 '';
            const baseAttr = fileNameAttr.replace(/\.pnote$/i, '');

            const nameEl = row.querySelector('.files-list__row-name-') ||
                           row.querySelector('.files-list__row-name-text') ||
                           row.querySelector('.nametext .innernametext') ||
                           row.querySelector('.file-name');

            const currentText = nameEl ? nameEl.textContent.trim() : '';
            const meta = findMetadata(fileId, fileNameAttr, baseAttr) || findMetadata(null, currentText, currentText.replace(/\.pnote$/i, ''));

            if (!meta || !meta.title) return;

            if (nameEl) {
                if (nameEl.textContent !== meta.title) {
                    nameEl.textContent = meta.title;
                }
                nameEl.title = `${meta.title} (${meta.name || fileNameAttr || fileId})`;
            }

            const rowLink = row.querySelector('a.files-list__row-name-link, button.files-list__row-name-link');
            if (rowLink) {
                rowLink.title = `${meta.title} (${meta.name || fileNameAttr || fileId})`;
            }
        });
    }

    function enrichFileRows() {
        // Check current directory from URL and fetch all titles for folder in one go
        const params = new URLSearchParams(window.location.search);
        const dir = params.get('dir') || '/';
        if (dir) {
            fetchFolderTitles(dir);
        }

        const idsToQuery = [];

        // Check Pinia store nodes
        const storeNodes = getStoreNodes();
        storeNodes.forEach(node => {
            if (isPaperNotesNode(node)) {
                const id = node.fileid || node.id;
                const name = node.basename || node.displayname || node.name || '';
                const base = name.replace(/\.pnote$/i, '');
                const meta = findMetadata(id, name, base);
                if (!meta) {
                    if (id && parseInt(id, 10) > 0) {
                        idsToQuery.push(parseInt(id, 10));
                    }
                } else if (meta.title && node.displayname !== meta.title) {
                    node.displayname = meta.title;
                }
            }
        });

        // Check DOM elements
        const rows = document.querySelectorAll('tr.files-list__row, tr[data-file], .files-list__grid-item, div.files-list__row');
        rows.forEach(row => {
            if (isPaperNotesRow(row)) {
                const fileId = row.getAttribute('data-cy-files-list-row-fileid') ||
                               row.getAttribute('data-id') ||
                               row.getAttribute('data-fileid');
                const fileNameAttr = row.getAttribute('data-cy-files-list-row-name') ||
                                     row.getAttribute('data-file') ||
                                     '';
                const baseAttr = fileNameAttr.replace(/\.pnote$/i, '');
                const meta = findMetadata(fileId, fileNameAttr, baseAttr);

                if (!meta && fileId && parseInt(fileId, 10) > 0) {
                    idsToQuery.push(parseInt(fileId, 10));
                }
            }
        });

        if (idsToQuery.length > 0) {
            queueFetchTitles(idsToQuery);
        } else {
            applyCachedTitles();
        }
    }

    function initPaperNotes() {
        registerModernFileActions();
        registerClassicFileActions();
        registerViewerHandler();
        enrichFileRows();
    }

    // Initialize immediately
    initPaperNotes();

    // Listen to files app lifecycle events
    window.addEventListener('DOMContentLoaded', initPaperNotes);
    window.addEventListener('files:fileactions:init', initPaperNotes);
    window.addEventListener('load', initPaperNotes);

    // Setup DOM MutationObserver to detect folder navigation, scrolling and row rendering
    const observer = new MutationObserver(function() {
        enrichFileRows();
    });

    const targetNode = document.getElementById('app-content') || document.body;
    if (targetNode) {
        observer.observe(targetNode, { childList: true, subtree: true });
    }

    // Periodic check during initialization
    let attempts = 0;
    const interval = setInterval(function() {
        initPaperNotes();
        attempts++;
        if (attempts > 20) {
            clearInterval(interval);
        }
    }, 500);

})(window, document);
