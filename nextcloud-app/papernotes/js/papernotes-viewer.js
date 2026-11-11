/**
 * PaperNotes Web Viewer for Nextcloud
 * Renders .pnote notebook files directly in the browser with full template and vector stroke support.
 */
(function(window, document) {
    'use strict';

    const PaperNotes = window.PaperNotes = window.PaperNotes || {};

    const TEMPLATE_LABELS = {
        de: {
            DONE: '✓',
            TIME: 'Zeit',
            TODOS: 'Aufgaben',
            SENDER: 'Absender',
            RECIPIENT: 'Empfänger',
            SUBJECT: 'Betreff',
            DATE: 'Datum',
            TITLE: 'Titel',
            PARTICIPANTS: 'Teilnehmer',
            AGENDA: 'Tagesordnung',
            NOTES: 'Notizen',
            DECISIONS: 'Beschlüsse',
            MON: 'Mo',
            TUE: 'Di',
            WED: 'Mi',
            THU: 'Do',
            FRI: 'Fr',
            SAT: 'Sa',
            SUN: 'So',
            TASK: 'Aufgabe',
            PRIORITY: 'Priorität',
            DUE: 'Fällig',
            ERROR: 'Fehler',
            CAUSE: 'Ursache',
            SOLUTION: 'Lösung',
            AUTHOR: 'Autor',
            QUOTES: 'Zitate',
            CUES: 'Stichworte',
            SUMMARY: 'Zusammenfassung',
            TOPIC: 'Thema',
            URGENT: 'Dringend',
            NOT_URGENT: 'Nicht dringend',
            IMPORTANT: 'Wichtig',
            NOT_IMPORTANT: 'Nicht wichtig',
            STRENGTHS: 'Stärken',
            WEAKNESSES: 'Schwächen',
            OPPORTUNITIES: 'Chancen',
            THREATS: 'Risiken',
            POINT: 'Punkt',
            ACTION: 'Aktion'
        },
        en: {
            DONE: '✓',
            TIME: 'Time',
            TODOS: 'Todos',
            SENDER: 'Sender',
            RECIPIENT: 'Recipient',
            SUBJECT: 'Subject',
            DATE: 'Date',
            TITLE: 'Title',
            PARTICIPANTS: 'Participants',
            AGENDA: 'Agenda',
            NOTES: 'Notes',
            DECISIONS: 'Decisions',
            MON: 'Mon',
            TUE: 'Tue',
            WED: 'Wed',
            THU: 'Thu',
            FRI: 'Fri',
            SAT: 'Sat',
            SUN: 'Sun',
            TASK: 'Task',
            PRIORITY: 'Priority',
            DUE: 'Due',
            ERROR: 'Error',
            CAUSE: 'Cause',
            SOLUTION: 'Solution',
            AUTHOR: 'Author',
            QUOTES: 'Quotes',
            CUES: 'Cues',
            SUMMARY: 'Summary',
            TOPIC: 'Topic',
            URGENT: 'Urgent',
            NOT_URGENT: 'Not urgent',
            IMPORTANT: 'Important',
            NOT_IMPORTANT: 'Not important',
            STRENGTHS: 'Strengths',
            WEAKNESSES: 'Weaknesses',
            OPPORTUNITIES: 'Opportunities',
            THREATS: 'Threats',
            POINT: 'Point',
            ACTION: 'Action'
        }
    };

    // Texts of the viewer UI. Every key must exist in both languages (checked by tests/run_tests.php).
    // {name} placeholders are replaced by tr(); the result is plain text, never markup.
    const VIEWER_TEXTS = {
        de: {
            defaultTitle: 'Notiz',
            audioDefault: 'Aufnahme',
            loading: 'Notiz wird geladen ...',
            errNotFound: 'Die Notiz wurde nicht gefunden oder du hast keinen Zugriff darauf.',
            errDenied: 'Du darfst diese Notiz nicht öffnen.',
            errMalformed: 'Die Notizdatei ist beschädigt oder hat ein nicht unterstütztes Format.',
            errGeneric: 'Die Notiz konnte nicht geladen werden.',
            close: 'Schließen',
            closeTitle: 'Schließen (Esc)',
            prevPage: 'Vorherige Seite (Pfeil links)',
            nextPage: 'Nächste Seite (Pfeil rechts)',
            pageIndicator: 'Seite {page} / {total}',
            zoomOut: 'Verkleinern (-)',
            zoomIn: 'Vergrößern (+)',
            fit: 'Anpassen',
            fitTitle: 'An Fenster anpassen',
            pdfTitle: 'Als PDF herunterladen',
            pdfButton: 'PDF herunterladen',
            pagesHeader: 'Seiten ({total})',
            thumbTitle: 'Seite {page}',
            pdfAudioConfirm: 'Die Notiz enthält Aufnahmen.\n\nSollen sie als Anhänge an den Seiten des PDFs eingebettet werden?\n\nOK = mit Anhängen, Abbrechen = nur das PDF'
        },
        en: {
            defaultTitle: 'Note',
            audioDefault: 'Recording',
            loading: 'Loading note ...',
            errNotFound: 'The note was not found or you do not have access to it.',
            errDenied: 'You are not allowed to open this note.',
            errMalformed: 'The note file is damaged or has an unsupported format.',
            errGeneric: 'The note could not be loaded.',
            close: 'Close',
            closeTitle: 'Close (Esc)',
            prevPage: 'Previous page (left arrow)',
            nextPage: 'Next page (right arrow)',
            pageIndicator: 'Page {page} / {total}',
            zoomOut: 'Zoom out (-)',
            zoomIn: 'Zoom in (+)',
            fit: 'Fit',
            fitTitle: 'Fit to window',
            pdfTitle: 'Download as PDF',
            pdfButton: 'Download PDF',
            pagesHeader: 'Pages ({total})',
            thumbTitle: 'Page {page}',
            pdfAudioConfirm: 'This note contains recordings.\n\nDo you want to embed them as attachments on the pages of the PDF?\n\nOK = with attachments, Cancel = PDF only'
        }
    };

    /** UI language: German for German Nextcloud users, English for everyone else. */
    function currentLang() {
        let code = '';
        try {
            if (window.OC && typeof window.OC.getLanguage === 'function') {
                code = window.OC.getLanguage() || '';
            }
        } catch (e) {}
        if (!code) code = document.documentElement.lang || '';
        return String(code).substring(0, 2).toLowerCase() === 'de' ? 'de' : 'en';
    }

    /** Returns the text for a key in the UI language (English if missing). Plain text, to be escaped for HTML. */
    function tr(key, vars) {
        const table = VIEWER_TEXTS[currentLang()];
        let text = (table && table[key]) || VIEWER_TEXTS.en[key] || key;
        if (vars) {
            text = text.replace(/\{(\w+)\}/g, (m, name) => (name in vars ? String(vars[name]) : m));
        }
        return text;
    }

    /** Maps the HTTP status of a failed note request to a fixed, translated message (server texts are never shown). */
    function loadErrorText(status) {
        switch (status) {
            case 404: return tr('errNotFound');
            case 403: return tr('errDenied');
            case 422: return tr('errMalformed');
            default: return tr('errGeneric');
        }
    }

    /**
     * Converts a 32-bit ARGB signed integer into an rgba() CSS string.
     */
    function argbToRgba(colorInt) {
        const uColor = colorInt >>> 0;
        let a = ((uColor >> 24) & 0xFF) / 255.0;
        const r = (uColor >> 16) & 0xFF;
        const g = (uColor >> 8) & 0xFF;
        const b = uColor & 0xFF;
        if (a === 0 && colorInt !== 0) {
            a = 1.0;
        }
        return `rgba(${r}, ${g}, ${b}, ${a.toFixed(3)})`;
    }

    /**
     * Calculates geometry shapes for all 21 PaperNotes templates matching TemplateGeometry.kt.
     */
    function buildTemplateShapes(templateId, w, h) {
        const dp = 1.41875;
        const mm = 8.937;
        const textPx = 20.0;

        const fine = [];
        const main = [];
        const head = [];
        const dots = [];
        const circles = [];
        const labels = [];

        // Maßstab der HiNote-Vorlagen (Entwurfsmaß 702 x 840, oben links verankert).
        const ds = w / 702.0;
        const margin = Math.min(12.0 * dp, Math.min(w, h) / 10.0);
        const pad = Math.min(6.0 * dp, margin);
        const fl = margin;
        const ft = margin;
        const fr = w - margin;
        const fb = h - margin;
        const fw = fr - fl;
        const fh = fb - ft;

        function addSeg(target, x1, y1, x2, y2) {
            target.push(
                Math.max(0, Math.min(w, x1)),
                Math.max(0, Math.min(h, y1)),
                Math.max(0, Math.min(w, x2)),
                Math.max(0, Math.min(h, y2))
            );
        }

        function rect(kind, l, t, r, b) {
            const target = kind === 0 ? fine : (kind === 2 ? head : main);
            addSeg(target, l, t, r, t);
            addSeg(target, l, b, r, b);
            addSeg(target, l, t, l, b);
            addSeg(target, r, t, r, b);
        }

        function hline(kind, y, x1, x2) {
            const target = kind === 0 ? fine : (kind === 2 ? head : main);
            addSeg(target, x1, y, x2, y);
        }

        function vline(kind, x, y1, y2) {
            const target = kind === 0 ? fine : (kind === 2 ? head : main);
            addSeg(target, x, y1, x, y2);
        }

        function addLabel(lbl, x, y, centered, right) {
            labels.push({
                label: lbl,
                x: Math.max(0, Math.min(w, x)),
                y: Math.max(0, Math.min(h, y)),
                centered: centered,
                right: !!right
            });
        }

        function labelRight(lbl, r, t, cellH) {
            addLabel(lbl, r - pad, t + cellH / 2.0 + textPx * 0.35, false, true);
        }

        function labelLeft(lbl, l, t, cellH) {
            addLabel(lbl, l + pad, t + cellH / 2.0 + textPx * 0.35, false);
        }

        function labelCenter(lbl, l, r, t, cellH) {
            addLabel(lbl, (l + r) / 2.0, t + cellH / 2.0 + textPx * 0.35, true);
        }

        function lines(kind, from, step, to, x1, x2) {
            const s = Math.max(3.0, step);
            for (let y = from; y < to; y += s) {
                hline(kind, y, x1, x2);
            }
        }

        function table(hdrH, rowH, colXs, hdrLabels) {
            const rh = Math.max(3.0, rowH);
            const rows = Math.floor((fh - hdrH) / rh);
            const bottom = ft + hdrH + rows * rh;
            rect(1, fl, ft, fr, bottom);
            hline(2, ft + hdrH, fl, fr);
            colXs.forEach(x => vline(1, x, ft, bottom));
            for (let k = 1; k < rows; k++) {
                hline(1, ft + hdrH + k * rh, fl, fr);
            }
            const bounds = [fl, ...colXs, fr];
            hdrLabels.forEach((lbl, idx) => {
                labelCenter(lbl, bounds[idx], bounds[idx + 1], ft, hdrH);
            });
            return rows;
        }

        switch (templateId) {
            case 'lines_wide':
                lines(1, 56.0 * dp, 56.0 * dp, h, 0.0, w);
                break;
            case 'lines_narrow':
                lines(1, 36.0 * dp, 36.0 * dp, h, 0.0, w);
                break;
            case 'dotted': {
                const step = Math.max(3.0, 36.0 * dp);
                const nx = Math.floor(w / step);
                const ny = Math.floor(h / step);
                if (nx >= 1 && ny >= 1) {
                    const x0 = (w - (nx - 1) * step) / 2.0;
                    const y0 = (h - (ny - 1) * step) / 2.0;
                    for (let j = 0; j < ny; j++) {
                        for (let i = 0; i < nx; i++) {
                            dots.push(x0 + i * step, y0 + j * step);
                        }
                    }
                }
                break;
            }
            case 'handwriting': {
                const step = 48.0 * dp;
                lines(1, step, step, h, 0.0, w);
                for (let y = step; y < h; y += step) {
                    hline(0, y - step / 2.0, 0.0, w);
                }
                vline(1, Math.min(60.0 * dp, w / 4.0), 0.0, h);
                break;
            }
            case 'millimeter': {
                const step = Math.max(3.0, mm);
                const countX = Math.floor(w / step);
                for (let i = 1; i <= countX; i++) {
                    vline(i % 5 === 0 ? 1 : 0, i * step, 0.0, h);
                }
                const countY = Math.floor(h / step);
                for (let j = 1; j <= countY; j++) {
                    hline(j % 5 === 0 ? 1 : 0, j * step, 0.0, w);
                }
                break;
            }
            case 'grid': {
                const step = Math.max(3.0, 5.0 * mm);
                for (let x = step; x < w; x += step) vline(1, x, 0.0, h);
                for (let y = step; y < h; y += step) hline(1, y, 0.0, w);
                break;
            }
            case 'letter': {
                const l = 0.1 * w;
                const r = 0.9 * w;
                const mid = 0.55 * w;
                const top = 0.05 * h;
                const senderH = 28.0 * dp;
                rect(1, l, top, mid, top + senderH);
                labelLeft('SENDER', l, top, senderH);
                const recTop = top + senderH;
                const recBottom = recTop + 110.0 * dp;
                rect(1, l, recTop, mid, recBottom);
                labelLeft('RECIPIENT', l, recTop, senderH);
                rect(1, 0.62 * w, top, r, top + senderH);
                labelLeft('DATE', 0.62 * w, top, senderH);
                const subjectY = recBottom + 44.0 * dp;
                hline(2, subjectY, l, r);
                addLabel('SUBJECT', l + pad, subjectY - pad, false);
                [1.0 / 3.0, 0.5, 2.0 / 3.0].forEach(frac => {
                    hline(1, frac * h, 0.0, Math.min(12.0 * dp, w / 4.0));
                });
                break;
            }
            case 'todo': {
                const hdr = 44.0 * dp;
                const rowH = 68.0 * dp;
                const c1 = fl + 0.09 * fw;
                const c2 = fl + 0.32 * fw;
                const rows = table(hdr, rowH, [c1, c2], ['DONE', 'TIME', 'TODOS']);
                const circleR = Math.min(11.0 * dp, (c1 - fl) / 3.0);
                for (let k = 0; k < rows; k++) {
                    circles.push((fl + c1) / 2.0, ft + hdr + (k + 0.5) * rowH, circleR);
                }
                break;
            }
            case 'tasks': {
                const hdr = 44.0 * dp;
                const rowH = 56.0 * dp;
                const c1 = fl + 0.08 * fw;
                const due = fr - 0.16 * fw;
                const prio = due - 0.12 * fw;
                const rows = table(hdr, rowH, [c1, prio, due], ['DONE', 'TASK', 'PRIORITY', 'DUE']);
                const half = Math.min(10.0 * dp, (c1 - fl) / 3.0);
                for (let k = 0; k < rows; k++) {
                    const cy = ft + hdr + (k + 0.5) * rowH;
                    const cx = (fl + c1) / 2.0;
                    rect(1, cx - half, cy - half, cx + half, cy + half);
                }
                break;
            }
            case 'error_log': {
                const hdr = 44.0 * dp;
                const x1 = fl + 0.14 * fw;
                const rest = (fr - x1) / 3.0;
                table(hdr, 64.0 * dp, [x1, x1 + rest, x1 + 2.0 * rest], ['DATE', 'ERROR', 'CAUSE', 'SOLUTION']);
                break;
            }
            case 'meeting': {
                const hdr = 44.0 * dp;
                const sec = 30.0 * dp;
                const row = 40.0 * dp;
                rect(1, fl, ft, fr, fb);
                const y1 = ft + hdr;
                const y2 = y1 + hdr;
                hline(1, y1, fl, fr);
                labelLeft('TITLE', fl, ft, hdr);
                const midX = (fl + fr) / 2.0;
                vline(1, midX, y1, y2);
                labelLeft('DATE', fl, y1, hdr);
                labelLeft('PARTICIPANTS', midX, y1, hdr);
                hline(2, y2, fl, fr);
                labelLeft('AGENDA', fl, y2, sec);
                const agendaEnd = y2 + sec + 5.0 * row;
                lines(0, y2 + sec + row, row, agendaEnd, fl, fr);
                const decTop = Math.max(agendaEnd + sec, fb - 0.22 * fh);
                hline(2, agendaEnd, fl, fr);
                labelLeft('NOTES', fl, agendaEnd, sec);
                lines(0, agendaEnd + sec + row, row, decTop, fl, fr);
                hline(2, decTop, fl, fr);
                labelLeft('DECISIONS', fl, decTop, sec);
                lines(0, decTop + sec + row, row, fb, fl, fr);
                break;
            }
            case 'weekly': {
                const days = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
                const rowH = fh / 7.0;
                const lc = fl + 0.1 * fw;
                const cell = 40.0 * dp;
                rect(1, fl, ft, fr, fb);
                vline(1, lc, ft, fb);
                days.forEach((day, idx) => {
                    const top = ft + idx * rowH;
                    if (idx > 0) hline(2, top, fl, fr);
                    labelCenter(day, fl, lc, top, cell);
                    lines(0, top + cell, cell, top + rowH - 1.0, lc, fr);
                });
                break;
            }
            case 'monthly': {
                const days = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
                const titleH = 44.0 * dp;
                const dayH = 32.0 * dp;
                const gridTop = ft + titleH + dayH;
                const colW = fw / 7.0;
                const rowH = (fb - gridTop) / 6.0;
                rect(1, fl, ft, fr, fb);
                hline(1, ft + titleH, fl, fr);
                hline(2, gridTop, fl, fr);
                for (let c = 1; c < 7; c++) vline(1, fl + c * colW, ft + titleH, fb);
                for (let r = 1; r < 6; r++) hline(1, gridTop + r * rowH, fl, fr);
                days.forEach((day, c) => {
                    labelCenter(day, fl + c * colW, fl + (c + 1) * colW, ft + titleH, dayH);
                });
                break;
            }
            case 'reading_notes': {
                const hdr = 44.0 * dp;
                const sec = 30.0 * dp;
                const row = 40.0 * dp;
                rect(1, fl, ft, fr, fb);
                const y1 = ft + hdr;
                const y2 = y1 + hdr;
                hline(1, y1, fl, fr);
                labelLeft('TITLE', fl, ft, hdr);
                const midX = (fl + fr) / 2.0;
                vline(1, midX, y1, y2);
                labelLeft('AUTHOR', fl, y1, hdr);
                labelLeft('DATE', midX, y1, hdr);
                hline(2, y2, fl, fr);
                labelLeft('QUOTES', fl, y2, sec);
                const quotesEnd = y2 + 0.4 * (fb - y2);
                lines(0, y2 + sec + row, row, quotesEnd, fl, fr);
                hline(2, quotesEnd, fl, fr);
                labelLeft('NOTES', fl, quotesEnd, sec);
                lines(0, quotesEnd + sec + row, row, fb, fl, fr);
                break;
            }
            case 'cornell': {
                const hdr = 56.0 * dp;
                const sec = 30.0 * dp;
                const cueX = fl + 0.28 * fw;
                const summaryTop = fb - 0.2 * fh;
                rect(1, fl, ft, fr, fb);
                hline(2, ft + hdr, fl, fr);
                labelLeft('TOPIC', fl, ft, hdr);
                vline(1, cueX, ft + hdr, summaryTop);
                hline(2, summaryTop, fl, fr);
                labelLeft('CUES', fl, ft + hdr, sec);
                labelLeft('NOTES', cueX, ft + hdr, sec);
                labelLeft('SUMMARY', fl, summaryTop, sec);
                break;
            }
            case 'character_grid': {
                const x0 = 21.5 * ds;
                const colW = 66.0 * ds;
                const rowH = 67.0 * ds;
                const pitch = 80.67 * ds;
                const y0 = 23.5 * ds;
                const dash = 4.0 * ds;
                const dashPitch = 6.0 * ds;
                for (let r = 0; r < 10; r++) {
                    const top = y0 + r * pitch;
                    const bottom = top + rowH;
                    hline(1, top, x0, x0 + 10 * colW);
                    hline(1, bottom, x0, x0 + 10 * colW);
                    for (let c = 0; c <= 10; c++) vline(1, x0 + c * colW, top, bottom);
                    const cy = top + rowH / 2.0;
                    for (let c = 0; c < 10; c++) {
                        const left = x0 + c * colW;
                        const cx = left + colW / 2.0;
                        for (let k = 0; k < 11; k++) {
                            const a = top + k * dashPitch;
                            vline(0, cx, a, Math.min(a + dash, bottom));
                            const b = left + k * dashPitch;
                            hline(0, cy, b, Math.min(b + dash, left + colW));
                        }
                    }
                }
                break;
            }
            case 'staff': {
                const x1 = 25.0 * ds;
                const x2 = 678.0 * ds;
                for (let g = 0; g < 10; g++) {
                    const top = (116.479 + g * 72.78) * ds;
                    for (let j = 0; j < 5; j++) hline(1, top + j * 8.0 * ds, x1, x2);
                }
                break;
            }
            case 'time_management': {
                vline(1, 351.0 * ds, 62.0 * ds, 772.0 * ds);
                hline(1, 420.0 * ds, 0, w);
                labelLeft('URGENT', 351.0 * ds, 40.0 * ds, 40.0 * ds);
                labelLeft('NOT_URGENT', 351.0 * ds, 761.0 * ds, 40.0 * ds);
                labelLeft('NOT_IMPORTANT', 0, 418.0 * ds, 40.0 * ds);
                labelRight('IMPORTANT', w, 418.0 * ds, 40.0 * ds);
                break;
            }
            case 'point_sheet': {
                const l = 24.0 * ds;
                const r = 681.0 * ds;
                const mid = 351.3 * ds;
                rect(1, l, 24.0 * ds, r, 816.0 * ds);
                hline(1, 72.0 * ds, l, r);
                hline(2, 117.0 * ds, l, r);
                vline(1, mid, 72.0 * ds, 816.0 * ds);
                labelCenter('POINT', l, mid, 72.0 * ds, 45.0 * ds);
                labelCenter('ACTION', mid, r, 72.0 * ds, 45.0 * ds);
                break;
            }
            case 'swot': {
                vline(1, 351.0 * ds, 0, 840.0 * ds);
                hline(1, 420.0 * ds, 0, w);
                labelLeft('STRENGTHS', 0, 8.0 * ds, 44.0 * ds);
                labelLeft('WEAKNESSES', 351.0 * ds, 8.0 * ds, 44.0 * ds);
                labelLeft('OPPORTUNITIES', 0, 428.0 * ds, 44.0 * ds);
                labelLeft('THREATS', 351.0 * ds, 428.0 * ds, 44.0 * ds);
                break;
            }
            case 'blank':
            default:
                break;
        }

        return { fine, main, head, dots, circles, labels };
    }

    // ------------------------------------------------------------------
    // Page content besides template and strokes (B1): images, text boxes; recording helpers for the player
    // ------------------------------------------------------------------

    const TEXT_PADDING = 4;
    const INDENT_EM = 1.5;
    const LINE_HEIGHT = 1.17;
    const FONT_STACK = 'Helvetica, Arial, sans-serif';
    const SPAN_BOLD = 0, SPAN_ITALIC = 1, SPAN_UNDERLINE = 2, SPAN_COLOR = 3, SPAN_SIZE = 4;
    const LIST_BULLET = 1, LIST_NUMBER = 2, LIST_LETTER = 3, LIST_SQUARE = 4;
    const widthCache = {};

    function cssColor(argb) {
        const u = argb >>> 0;
        return `rgb(${(u >> 16) & 0xFF}, ${(u >> 8) & 0xFF}, ${u & 0xFF})`;
    }

    function charWidth(ctx, font, ch) {
        const key = font + '|' + ch;
        let w = widthCache[key];
        if (w === undefined) {
            ctx.font = font;
            w = ctx.measureText(ch).width;
            widthCache[key] = w;
        }
        return w;
    }

    function formatTime(ms) {
        const total = Math.floor(Math.max(0, ms) / 1000);
        const h = Math.floor(total / 3600);
        const m = Math.floor((total % 3600) / 60);
        const sec = total % 60;
        const two = n => (n < 10 ? '0' : '') + n;
        return h > 0 ? `${h}:${two(m)}:${two(sec)}` : `${m}:${two(sec)}`;
    }

    function audioLabel(audio) {
        const name = String(audio.title || '').trim() || tr('audioDefault');
        return audio.duration > 0 ? `${name} (${formatTime(audio.duration)})` : name;
    }

    function listNumber(dense, i) {
        const me = dense[i];
        let n = 1;
        for (let k = i - 1; k >= 0; k--) {
            const p = dense[k];
            if (p.level > me.level) continue;
            if (p.level < me.level || p.list !== me.list) break;
            n++;
        }
        return n;
    }

    function listMarker(dense, i) {
        const p = dense[i];
        if (!p) return '';
        switch (p.list) {
            case LIST_BULLET: return p.level % 2 === 0 ? '\u2022' : '\u2013';
            case LIST_SQUARE: return p.level % 2 === 0 ? '\u25A0' : '\u25A1';
            case LIST_NUMBER: return listNumber(dense, i) + '.';
            case LIST_LETTER: return String.fromCharCode(97 + (listNumber(dense, i) - 1) % 26) + '.';
            default: return '';
        }
    }

    function drawMarker(ctx, marker, left, baseline, size, unit) {
        ctx.fillStyle = '#000000';
        ctx.strokeStyle = '#000000';
        const first = marker.charAt(0);
        if (first === '\u2022' || first === '\u25A0' || first === '\u25A1') {
            const cx = left + unit * 0.3;
            const cy = baseline - size * 0.4;
            ctx.beginPath();
            if (first === '\u2022') {
                ctx.arc(cx, cy, size * 0.17, 0, Math.PI * 2);
                ctx.fill();
            } else {
                const h = size * 0.178;
                if (first === '\u25A0') {
                    ctx.fillRect(cx - h, cy - h, 2 * h, 2 * h);
                } else {
                    ctx.lineWidth = size * 0.06;
                    ctx.strokeRect(cx - h, cy - h, 2 * h, 2 * h);
                }
            }
            return;
        }
        ctx.font = `${size}px ${FONT_STACK}`;
        ctx.textAlign = 'left';
        ctx.textBaseline = 'alphabetic';
        ctx.fillText(marker, left, baseline);
    }

    /**
     * Draws one text box like the app: background, padding 4, indent 1.5 em per level, list markers, alignment and the
     * formats bold, italic, underline, colour and relative size. Text is clipped to the box.
     */
    function drawTextbox(ctx, tb) {
        const w = Number(tb.w), h = Number(tb.h), fontSize = Number(tb.size) || 20;
        if (!(w > 0) || !(h > 0)) return;
        const x = Number(tb.x) || 0, y = Number(tb.y) || 0;

        ctx.save();
        if (tb.bgColor) {
            ctx.fillStyle = cssColor(tb.bgColor);
            ctx.fillRect(x, y, w, h);
        }
        const text = String(tb.text || '');
        if (text === '') {
            ctx.restore();
            return;
        }
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        ctx.clip();

        const n = text.length;
        const bold = new Array(n).fill(false);
        const italic = new Array(n).fill(false);
        const under = new Array(n).fill(false);
        const color = new Array(n).fill(null);
        const sizePct = new Array(n).fill(100);
        (tb.spans || []).forEach(sp => {
            const type = sp[0], from = Math.max(0, sp[1]), to = Math.min(n, sp[2]);
            for (let i = from; i < to; i++) {
                if (type === SPAN_BOLD) bold[i] = true;
                else if (type === SPAN_ITALIC) italic[i] = true;
                else if (type === SPAN_UNDERLINE) under[i] = true;
                else if (type === SPAN_COLOR) color[i] = sp[3];
                else if (type === SPAN_SIZE) sizePct[i] = Math.max(1, sp[3]);
            }
        });

        // Paragraphs: arrays of char indices
        const paragraphs = [[]];
        for (let i = 0; i < n; i++) {
            if (text.charAt(i) === '\n') paragraphs.push([]);
            else paragraphs[paragraphs.length - 1].push(i);
        }
        const paraMap = {};
        (tb.paras || []).forEach(pf => { paraMap[pf[0]] = { align: pf[1], list: pf[2], level: pf[3] }; });
        const dense = paragraphs.map((_, idx) => paraMap[idx] || { align: 0, list: 0, level: 0 });

        const fontOf = i => `${italic[i] ? 'italic ' : ''}${bold[i] ? 'bold ' : ''}${fontSize * sizePct[i] / 100}px ${FONT_STACK}`;
        const widthOf = i => charWidth(ctx, fontOf(i), text.charAt(i));

        const unit = fontSize * INDENT_EM;
        const left = x + TEXT_PADDING;
        const innerW = w - 2 * TEXT_PADDING;
        const bottom = y + h;
        let top = y + TEXT_PADDING;

        ctx.textAlign = 'left';
        ctx.textBaseline = 'alphabetic';

        for (let pi = 0; pi < paragraphs.length; pi++) {
            const para = paragraphs[pi];
            const fmt = dense[pi];
            const steps = fmt.list !== 0 ? fmt.level + 1 : fmt.level;
            const indent = steps * unit;
            const avail = Math.max(1, innerW - indent);
            const marker = listMarker(dense, pi);

            // Greedy word wrap
            const lines = [];
            if (para.length > 0) {
                let start = 0, lw = 0, breakAt = -1;
                for (let k = 0; k < para.length; k++) {
                    const cw = widthOf(para[k]);
                    const isSpace = text.charAt(para[k]) === ' ';
                    if (!isSpace && lw + cw > avail && k > start) {
                        if (breakAt > start) {
                            lines.push([start, breakAt]);
                            start = breakAt;
                        } else {
                            lines.push([start, k]);
                            start = k;
                        }
                        lw = 0;
                        for (let q = start; q < k; q++) lw += widthOf(para[q]);
                        breakAt = -1;
                    }
                    lw += cw;
                    if (isSpace) breakAt = k + 1;
                }
                lines.push([start, para.length]);
            }

            if (lines.length === 0) {
                top += fontSize * LINE_HEIGHT;
                continue;
            }

            for (let li = 0; li < lines.length; li++) {
                if (top > bottom) {
                    ctx.restore();
                    return;
                }
                const from = lines[li][0];
                let to = lines[li][1];
                let maxSize = fontSize;
                for (let k = from; k < to; k++) maxSize = Math.max(maxSize, fontSize * sizePct[para[k]] / 100);
                const baseline = top + maxSize * 0.93;
                while (to > from && text.charAt(para[to - 1]) === ' ') to--;
                let lineW = 0;
                for (let k = from; k < to; k++) lineW += widthOf(para[k]);

                let lx = left + indent;
                if (fmt.list === 0 && fmt.align === 1) lx = left + indent + Math.max(0, (avail - lineW) / 2);
                else if (fmt.list === 0 && fmt.align === 2) lx = left + innerW - lineW;

                if (li === 0 && marker) drawMarker(ctx, marker, left + fmt.level * unit, baseline, fontSize, unit);

                let cx = lx;
                let k = from;
                while (k < to) {
                    const first = para[k];
                    let j = k;
                    let run = '';
                    let runW = 0;
                    while (j < to) {
                        const c = para[j];
                        if (fontOf(c) !== fontOf(first) || under[c] !== under[first] || color[c] !== color[first]) break;
                        run += text.charAt(c);
                        runW += widthOf(c);
                        j++;
                    }
                    ctx.font = fontOf(first);
                    ctx.fillStyle = color[first] === null ? '#000000' : cssColor(color[first]);
                    ctx.fillText(run, cx, baseline);
                    if (under[first]) {
                        const sz = fontSize * sizePct[first] / 100;
                        ctx.strokeStyle = ctx.fillStyle;
                        ctx.lineWidth = Math.max(1, sz * 0.06);
                        ctx.beginPath();
                        ctx.moveTo(cx, baseline + sz * 0.12);
                        ctx.lineTo(cx + runW, baseline + sz * 0.12);
                        ctx.stroke();
                    }
                    cx += runW;
                    k = j;
                }
                top += maxSize * LINE_HEIGHT;
            }
        }
        ctx.restore();
    }

    /**
     * Renders a PaperNotes page to an HTML5 Canvas context.
     */
    function renderPage(ctx, page, options) {
        const opt = options || {};
        const width = page.width || 1404;
        const height = page.height || 1872;
        const lang = opt.lang || 'en';
        const labelsDict = TEMPLATE_LABELS[lang] || TEMPLATE_LABELS.en;

        // Background: Solid white
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, width, height);

        // 1. Template
        const templateId = page.template || 'blank';
        const shapes = buildTemplateShapes(templateId, width, height);

        // Fine lines (#DDDDDD, 0.75px)
        if (shapes.fine.length > 0) {
            ctx.strokeStyle = '#DDDDDD';
            ctx.lineWidth = 1.0;
            ctx.beginPath();
            for (let i = 0; i + 3 < shapes.fine.length; i += 4) {
                ctx.moveTo(shapes.fine[i], shapes.fine[i + 1]);
                ctx.lineTo(shapes.fine[i + 2], shapes.fine[i + 3]);
            }
            ctx.stroke();
        }

        // Main lines (#B0B0B0, 1.2px)
        if (shapes.main.length > 0) {
            ctx.strokeStyle = '#B0B0B0';
            ctx.lineWidth = 1.4;
            ctx.beginPath();
            for (let i = 0; i + 3 < shapes.main.length; i += 4) {
                ctx.moveTo(shapes.main[i], shapes.main[i + 1]);
                ctx.lineTo(shapes.main[i + 2], shapes.main[i + 3]);
            }
            ctx.stroke();
        }

        // Head lines (#707070, 2.0px)
        if (shapes.head.length > 0) {
            ctx.strokeStyle = '#707070';
            ctx.lineWidth = 2.0;
            ctx.beginPath();
            for (let i = 0; i + 3 < shapes.head.length; i += 4) {
                ctx.moveTo(shapes.head[i], shapes.head[i + 1]);
                ctx.lineTo(shapes.head[i + 2], shapes.head[i + 3]);
            }
            ctx.stroke();
        }

        // Dots (#909090)
        if (shapes.dots.length > 0) {
            ctx.fillStyle = '#909090';
            const dotR = 2.0;
            for (let i = 0; i + 1 < shapes.dots.length; i += 2) {
                ctx.beginPath();
                ctx.arc(shapes.dots[i], shapes.dots[i + 1], dotR, 0, Math.PI * 2);
                ctx.fill();
            }
        }

        // Circles (#A0A0A0)
        if (shapes.circles.length > 0) {
            ctx.strokeStyle = '#A0A0A0';
            ctx.lineWidth = 1.5;
            for (let i = 0; i + 2 < shapes.circles.length; i += 3) {
                ctx.beginPath();
                ctx.arc(shapes.circles[i], shapes.circles[i + 1], shapes.circles[i + 2], 0, Math.PI * 2);
                ctx.stroke();
            }
        }

        // Labels
        if (shapes.labels.length > 0) {
            ctx.fillStyle = '#808080';
            ctx.font = '20px sans-serif';
            shapes.labels.forEach(item => {
                const text = labelsDict[item.label] || item.label;
                ctx.textAlign = item.centered ? 'center' : (item.right ? 'right' : 'left');
                ctx.textBaseline = 'alphabetic';
                ctx.fillText(text, item.x, item.y);
            });
        }

        // 2. Images (below the strokes, as in the editor)
        if (opt.getImage) {
            (page.images || []).forEach(img => {
                const el = opt.getImage(img.file);
                if (el && el.complete && el.naturalWidth > 0) {
                    ctx.drawImage(el, img.x, img.y, img.w, img.h);
                }
            });
        }

        // 3. Strokes
        const strokes = page.strokes || [];
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';

        strokes.forEach(s => {
            const points = s.p || [];
            if (points.length === 0) return;

            ctx.strokeStyle = argbToRgba(s.c !== undefined ? s.c : -16777216);
            ctx.lineWidth = Math.max(0.5, s.w || 1.0);

            ctx.beginPath();
            ctx.moveTo(points[0][0], points[0][1]);

            if (points.length === 1) {
                ctx.lineTo(points[0][0] + 0.1, points[0][1]);
            } else {
                for (let i = 1; i < points.length; i++) {
                    ctx.lineTo(points[i][0], points[i][1]);
                }
            }
            ctx.stroke();
        });

        // 4. Text boxes above the strokes (recordings are not drawn; the viewer shows a player below the page)
        (page.textboxes || []).forEach(tb => drawTextbox(ctx, tb));
    }

    /**
     * PaperNotes Modal Viewer Class
     */
    class Viewer {
        constructor() {
            this.note = null;
            this.fileId = null;
            this.currentPageIndex = 0;
            this.zoomScale = 1.0;
            this.fitMode = 'fit-window'; // 'fit-window', 'fit-width', '100%'
            this.isSidebarOpen = true;
            this.modalEl = null;
            this.canvasEl = null;
            this.ctx = null;
            this.lang = currentLang();
            this.imageCache = {};
            this.refreshQueued = false;

            this.boundOnKeyDown = this.onKeyDown.bind(this);
            this.boundOnResize = this.onResize.bind(this);
        }

        open(fileId, noteData) {
            this.fileId = fileId;
            if (noteData) {
                this.initViewer(noteData);
            } else {
                this.showLoading();
                this.fetchNote(fileId);
            }
        }

        fetchNote(fileId) {
            const url = (window.OC && window.OC.generateUrl)
                ? window.OC.generateUrl('/apps/papernotes/api/notes/' + encodeURIComponent(fileId))
                : ('/apps/papernotes/api/notes/' + encodeURIComponent(fileId));

            const headers = {};
            if (window.OC && window.OC.requestToken) {
                headers['requesttoken'] = window.OC.requestToken;
            }

            fetch(url, { headers, credentials: 'same-origin' })
                .then(res => {
                    if (!res.ok) {
                        const failure = new Error('HTTP error');
                        failure.status = res.status;
                        throw failure;
                    }
                    return res.json();
                })
                .then(json => {
                    if (json.status !== 'success' || !json.data) {
                        throw new Error('Invalid server response');
                    }
                    this.initViewer(json.data);
                })
                .catch(err => {
                    this.showError(loadErrorText(err && err.status));
                });
        }

        mediaUrl(name) {
            const path = '/apps/papernotes/api/notes/' + encodeURIComponent(this.fileId) + '/media/' + encodeURIComponent(name);
            return (window.OC && window.OC.generateUrl) ? window.OC.generateUrl(path) : path;
        }

        /** Returns the (possibly still loading) image element for a media file; re-renders once it has loaded. */
        getImage(name) {
            let el = this.imageCache[name];
            if (el === undefined) {
                el = new Image();
                el.onload = () => this.queueRefresh();
                el.onerror = () => { el.failed = true; };
                el.src = this.mediaUrl(name);
                this.imageCache[name] = el;
            }
            return el.failed ? null : el;
        }

        queueRefresh() {
            if (this.refreshQueued || !this.modalEl) return;
            this.refreshQueued = true;
            window.requestAnimationFrame(() => {
                this.refreshQueued = false;
                if (!this.modalEl) return;
                this.renderCurrentPage();
                this.renderThumbnails();
            });
        }

        renderOptions() {
            return { lang: this.lang, getImage: name => this.getImage(name) };
        }

        initViewer(noteData) {
            this.note = noteData;
            this.imageCache = {};
            this.currentPageIndex = 0;
            this.buildDOM();
            this.renderCurrentPage();
            this.renderThumbnails();
            window.addEventListener('keydown', this.boundOnKeyDown);
            window.addEventListener('resize', this.boundOnResize);
        }

        showLoading() {
            this.close();
            const modal = document.createElement('div');
            modal.id = 'papernotes-modal';
            modal.className = 'papernotes-modal-loading';
            modal.innerHTML = `
                <div class="papernotes-loading-box">
                    <div class="papernotes-spinner"></div>
                    <div class="papernotes-loading-text"></div>
                </div>
            `;
            modal.querySelector('.papernotes-loading-text').textContent = tr('loading');
            document.body.appendChild(modal);
            this.modalEl = modal;
        }

        showError(message) {
            if (!this.modalEl) {
                this.modalEl = document.createElement('div');
                this.modalEl.id = 'papernotes-modal';
                document.body.appendChild(this.modalEl);
            }
            this.modalEl.className = 'papernotes-modal-error';
            this.modalEl.innerHTML = `
                <div class="papernotes-error-box">
                    <div class="papernotes-error-icon">⚠️</div>
                    <div class="papernotes-error-message"></div>
                    <button class="papernotes-btn papernotes-btn-primary" id="papernotes-btn-close-err"></button>
                </div>
            `;
            // Texts are set as plain text, never interpreted as HTML.
            this.modalEl.querySelector('.papernotes-error-message').textContent = String(message);
            this.modalEl.querySelector('#papernotes-btn-close-err').textContent = tr('close');
            document.getElementById('papernotes-btn-close-err').addEventListener('click', () => this.close());
        }

        buildDOM() {
            if (this.modalEl) {
                this.modalEl.remove();
            }

            const title = (this.note.manifest && this.note.manifest.title) ? this.note.manifest.title : tr('defaultTitle');
            const pageIds = (this.note.manifest && this.note.manifest.pageIds) ? this.note.manifest.pageIds : Object.keys(this.note.pages || {});
            const totalPages = pageIds.length;
            const hasAudio = pageIds.some(id => ((this.note.pages || {})[id]?.audios || []).length > 0);

            const modal = document.createElement('div');
            modal.id = 'papernotes-modal';
            modal.className = 'papernotes-modal';
            modal.innerHTML = `
                <div class="papernotes-header">
                    <div class="papernotes-header-left">
                        <div class="papernotes-logo-icon"></div>
                        <div class="papernotes-title-box">
                            <span class="papernotes-title">${this.escapeHtml(title)}</span>
                            <span class="papernotes-folder">${this.escapeHtml(this.note.manifest?.folderPath || '')}</span>
                        </div>
                    </div>
                    <div class="papernotes-header-center">
                        <button class="papernotes-btn-icon" id="pn-btn-prev" title="${this.escapeHtml(tr('prevPage'))}">◀</button>
                        <span class="papernotes-page-indicator" id="pn-page-indicator">${this.escapeHtml(tr('pageIndicator', { page: 1, total: totalPages }))}</span>
                        <button class="papernotes-btn-icon" id="pn-btn-next" title="${this.escapeHtml(tr('nextPage'))}">▶</button>
                    </div>
                    <div class="papernotes-header-right">
                        <button class="papernotes-btn-icon" id="pn-btn-zoom-out" title="${this.escapeHtml(tr('zoomOut'))}">−</button>
                        <span class="papernotes-zoom-text" id="pn-zoom-text">100%</span>
                        <button class="papernotes-btn-icon" id="pn-btn-zoom-in" title="${this.escapeHtml(tr('zoomIn'))}">+</button>
                        <button class="papernotes-btn" id="pn-btn-fit" title="${this.escapeHtml(tr('fitTitle'))}">${this.escapeHtml(tr('fit'))}</button>
                        <button class="papernotes-btn papernotes-btn-pdf" id="pn-btn-pdf" title="${this.escapeHtml(tr('pdfTitle'))}">
                            <span class="pn-pdf-icon">📄</span> ${this.escapeHtml(tr('pdfButton'))}
                        </button>
                        <button class="papernotes-btn-icon papernotes-btn-close" id="pn-btn-close" title="${this.escapeHtml(tr('closeTitle'))}">✕</button>
                    </div>
                </div>
                <div class="papernotes-body">
                    <div class="papernotes-sidebar" id="pn-sidebar">
                        <div class="papernotes-sidebar-header">
                            <span>${this.escapeHtml(tr('pagesHeader', { total: totalPages }))}</span>
                        </div>
                        <div class="papernotes-thumbnails" id="pn-thumbnails"></div>
                    </div>
                    <div class="papernotes-main">
                        <div class="papernotes-viewport" id="pn-viewport">
                            <div class="papernotes-canvas-container" id="pn-canvas-container">
                                <canvas id="pn-canvas"></canvas>
                            </div>
                        </div>
                        <div class="papernotes-audio-panel" id="pn-audio-panel" hidden></div>
                    </div>
                </div>
            `;

            document.body.appendChild(modal);
            this.modalEl = modal;

            this.canvasEl = document.getElementById('pn-canvas');
            this.ctx = this.canvasEl.getContext('2d');

            // Wire event listeners
            document.getElementById('pn-btn-prev').addEventListener('click', () => this.prevPage());
            document.getElementById('pn-btn-next').addEventListener('click', () => this.nextPage());
            document.getElementById('pn-btn-zoom-in').addEventListener('click', () => this.setZoom(this.zoomScale * 1.2));
            document.getElementById('pn-btn-zoom-out').addEventListener('click', () => this.setZoom(this.zoomScale / 1.2));
            document.getElementById('pn-btn-fit').addEventListener('click', () => this.toggleFit());
            document.getElementById('pn-btn-pdf').addEventListener('click', () => this.onPdfClick(hasAudio));
            document.getElementById('pn-btn-close').addEventListener('click', () => this.close());

            // Viewport drag-to-pan & wheel zoom
            const viewport = document.getElementById('pn-viewport');
            let isDragging = false;
            let startX = 0, startY = 0, scrollLeft = 0, scrollTop = 0;

            viewport.addEventListener('mousedown', e => {
                if (e.target === this.canvasEl || e.target === viewport) {
                    isDragging = true;
                    startX = e.pageX - viewport.offsetLeft;
                    startY = e.pageY - viewport.offsetTop;
                    scrollLeft = viewport.scrollLeft;
                    scrollTop = viewport.scrollTop;
                }
            });

            window.addEventListener('mouseup', () => { isDragging = false; });
            viewport.addEventListener('mousemove', e => {
                if (!isDragging) return;
                e.preventDefault();
                const x = e.pageX - viewport.offsetLeft;
                const y = e.pageY - viewport.offsetTop;
                viewport.scrollLeft = scrollLeft - (x - startX);
                viewport.scrollTop = scrollTop - (y - startY);
            });

            viewport.addEventListener('wheel', e => {
                if (e.ctrlKey) {
                    e.preventDefault();
                    const factor = e.deltaY < 0 ? 1.1 : 0.9;
                    this.setZoom(this.zoomScale * factor);
                }
            }, { passive: false });
        }

        renderCurrentPage() {
            if (!this.note) return;
            const pageIds = this.note.manifest?.pageIds || Object.keys(this.note.pages || {});
            if (pageIds.length === 0) return;

            const pageId = pageIds[this.currentPageIndex];
            const page = this.note.pages[pageId];
            if (!page) return;

            const width = page.width || 1404;
            const height = page.height || 1872;

            // The player row below the page changes the free height, so it is built before the fit scale is computed.
            this.renderAudioPanel(page);

            // Apply fit mode on page switch if set
            if (this.fitMode === 'fit-window') {
                this.calculateFitScale(width, height);
            }

            const dpr = window.devicePixelRatio || 1.0;
            const displayW = width * this.zoomScale;
            const displayH = height * this.zoomScale;

            this.canvasEl.width = width * dpr;
            this.canvasEl.height = height * dpr;
            this.canvasEl.style.width = `${displayW}px`;
            this.canvasEl.style.height = `${displayH}px`;

            this.ctx.save();
            this.ctx.scale(dpr, dpr);
            renderPage(this.ctx, page, this.renderOptions());
            this.ctx.restore();

            // Update UI page indicator
            const indicator = document.getElementById('pn-page-indicator');
            if (indicator) {
                indicator.textContent = tr('pageIndicator', { page: this.currentPageIndex + 1, total: pageIds.length });
            }

            // Update zoom text
            const zoomText = document.getElementById('pn-zoom-text');
            if (zoomText) {
                zoomText.textContent = `${Math.round(this.zoomScale * 100)}%`;
            }

            // Update prev/next button states
            const prevBtn = document.getElementById('pn-btn-prev');
            const nextBtn = document.getElementById('pn-btn-next');
            if (prevBtn) prevBtn.disabled = (this.currentPageIndex === 0);
            if (nextBtn) nextBtn.disabled = (this.currentPageIndex >= pageIds.length - 1);

            // Highlight active thumbnail
            document.querySelectorAll('.papernotes-thumb-card').forEach((el, idx) => {
                if (idx === this.currentPageIndex) {
                    el.classList.add('active');
                    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                } else {
                    el.classList.remove('active');
                }
            });
        }

        /** Lists the recordings of the current page with a player each (the browser fetches the file when played). */
        renderAudioPanel(page) {
            const panel = document.getElementById('pn-audio-panel');
            if (!panel) return;
            const audios = page.audios || [];
            const key = audios.map(a => a.file).join('|');
            if (panel.dataset.key === key) return;
            panel.dataset.key = key;
            panel.innerHTML = '';
            panel.hidden = audios.length === 0;
            audios.forEach(a => {
                const row = document.createElement('div');
                row.className = 'papernotes-audio-row';
                const name = document.createElement('span');
                name.className = 'papernotes-audio-name';
                name.textContent = audioLabel(a);
                const player = document.createElement('audio');
                player.controls = true;
                player.preload = 'none';
                player.src = this.mediaUrl(a.file);
                row.appendChild(name);
                row.appendChild(player);
                panel.appendChild(row);
            });
        }

        renderThumbnails() {
            const container = document.getElementById('pn-thumbnails');
            if (!container || !this.note) return;
            container.innerHTML = '';

            const pageIds = this.note.manifest?.pageIds || Object.keys(this.note.pages || {});

            pageIds.forEach((pageId, index) => {
                const page = this.note.pages[pageId];
                if (!page) return;

                const card = document.createElement('div');
                card.className = `papernotes-thumb-card ${index === this.currentPageIndex ? 'active' : ''}`;
                card.title = tr('thumbTitle', { page: index + 1 });

                const thumbCanvas = document.createElement('canvas');
                const tw = 120;
                const th = Math.round(tw * ((page.height || 1872) / (page.width || 1404)));
                thumbCanvas.width = tw;
                thumbCanvas.height = th;

                const tCtx = thumbCanvas.getContext('2d');
                tCtx.scale(tw / (page.width || 1404), th / (page.height || 1872));
                renderPage(tCtx, page, this.renderOptions());

                const label = document.createElement('div');
                label.className = 'papernotes-thumb-label';
                label.textContent = `${index + 1}${page.bookmarked ? ' ★' : ''}`;

                card.appendChild(thumbCanvas);
                card.appendChild(label);

                card.addEventListener('click', () => {
                    this.currentPageIndex = index;
                    this.renderCurrentPage();
                });

                container.appendChild(card);
            });
        }

        calculateFitScale(pageW, pageH) {
            const viewport = document.getElementById('pn-viewport');
            if (!viewport) return;
            const vw = viewport.clientWidth - 40;
            const vh = viewport.clientHeight - 40;
            if (vw <= 0 || vh <= 0) return;

            const scaleW = vw / pageW;
            const scaleH = vh / pageH;
            this.zoomScale = Math.min(scaleW, scaleH, 1.5);
        }

        setZoom(scale) {
            this.fitMode = 'custom';
            this.zoomScale = Math.max(0.2, Math.min(3.0, scale));
            this.renderCurrentPage();
        }

        toggleFit() {
            if (this.fitMode === 'fit-window') {
                this.fitMode = '100%';
                this.zoomScale = 1.0;
            } else {
                this.fitMode = 'fit-window';
            }
            this.renderCurrentPage();
        }

        prevPage() {
            if (this.currentPageIndex > 0) {
                this.currentPageIndex--;
                this.renderCurrentPage();
            }
        }

        nextPage() {
            const pageIds = this.note.manifest?.pageIds || Object.keys(this.note.pages || {});
            if (this.currentPageIndex < pageIds.length - 1) {
                this.currentPageIndex++;
                this.renderCurrentPage();
            }
        }

        /** Notes with recordings ask whether the recordings should be attached to the PDF pages. */
        onPdfClick(hasAudio) {
            const withAudio = hasAudio && window.confirm(tr('pdfAudioConfirm'));
            this.downloadPdf(withAudio);
        }

        downloadPdf(withAudio) {
            if (!this.fileId) return;
            let url = (window.OC && window.OC.generateUrl)
                ? window.OC.generateUrl('/apps/papernotes/api/notes/' + encodeURIComponent(this.fileId) + '/pdf')
                : ('/apps/papernotes/api/notes/' + encodeURIComponent(this.fileId) + '/pdf');
            if (withAudio) {
                url += (url.includes('?') ? '&' : '?') + 'audio=1';
            }
            if (window.OC && window.OC.requestToken) {
                url += (url.includes('?') ? '&' : '?') + 'requesttoken=' + encodeURIComponent(window.OC.requestToken);
            }
            window.open(url, '_blank');
        }

        onKeyDown(e) {
            if (!this.modalEl) return;
            switch (e.key) {
                case 'Escape':
                    this.close();
                    break;
                case 'ArrowLeft':
                case 'PageUp':
                case 'k':
                    this.prevPage();
                    break;
                case 'ArrowRight':
                case 'PageDown':
                case 'j':
                    this.nextPage();
                    break;
                case '+':
                case '=':
                    this.setZoom(this.zoomScale * 1.2);
                    break;
                case '-':
                    this.setZoom(this.zoomScale / 1.2);
                    break;
                case '0':
                    this.toggleFit();
                    break;
            }
        }

        onResize() {
            if (this.fitMode === 'fit-window') {
                this.renderCurrentPage();
            }
        }

        close() {
            window.removeEventListener('keydown', this.boundOnKeyDown);
            window.removeEventListener('resize', this.boundOnResize);
            if (this.modalEl) {
                this.modalEl.remove();
                this.modalEl = null;
            }
        }

        escapeHtml(str) {
            return String(str || '')
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;');
        }
    }

    PaperNotes.Viewer = Viewer;
    PaperNotes.viewerInstance = new Viewer();
    PaperNotes.renderPage = renderPage;
    PaperNotes.drawTextbox = drawTextbox;

})(window, document);
