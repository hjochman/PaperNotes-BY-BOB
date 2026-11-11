/**
 * PaperNotes MIME type registration for Nextcloud.
 */
(function() {
    'use strict';

    var typeName = (typeof t === 'function') ? t('papernotes', 'PaperNote') : 'PaperNote';

    if (typeof OC !== 'undefined') {
        if (OC.MimeTypeList) {
            OC.MimeTypeList.names = OC.MimeTypeList.names || {};
            OC.MimeTypeList.names['application/x-papernotes'] = typeName;
            OC.MimeTypeList.names['application/x-pnote'] = typeName;
        }

        if (OC.MimeType) {
            OC.MimeType._mimeTypeNames = OC.MimeType._mimeTypeNames || {};
            OC.MimeType._mimeTypeNames['application/x-papernotes'] = typeName;
            OC.MimeType._mimeTypeNames['application/x-pnote'] = typeName;

            var pnoteIcon = (window.OC && typeof window.OC.imagePath === 'function')
                ? window.OC.imagePath('papernotes', 'pnote.svg')
                : '/apps/papernotes/img/pnote.svg';

            OC.MimeType._mimeTypeIcons = OC.MimeType._mimeTypeIcons || {};
            OC.MimeType._mimeTypeIcons['application/x-papernotes'] = pnoteIcon;
            OC.MimeType._mimeTypeIcons['application/x-pnote'] = pnoteIcon;
        }
    }
})();
