<?php
/**
 * Standalone template for PaperNotes full-screen viewer.
 */
?>
<div id="papernotes-standalone-root" data-file-id="<?php p($_['fileId']); ?>" data-lang="<?php p($_['lang']); ?>"></div>
<script>
    document.addEventListener('DOMContentLoaded', function() {
        var root = document.getElementById('papernotes-standalone-root');
        var fileId = root.getAttribute('data-file-id');
        if (window.PaperNotes && window.PaperNotes.viewerInstance) {
            window.PaperNotes.viewerInstance.open(fileId);
        }
    });
</script>
