/*
 * The feedback form, the same file in every application: The-Hub-Database/docs/feedback/feedback.js is the
 * copy the others are taken from, served by each as /feedback/feedback.js. Change it there and copy it out.
 *
 * The form posts without it - a plain form post the application answers with a page - so this only does
 * what a page round trip cannot: the count under the message, aria-invalid in step with :user-invalid,
 * the page the visitor came from, and a send that keeps them where they are. The post is
 * form-encoded, so the hidden _csrf field Thymeleaf writes into the form goes with it.
 */
(() => {
    const form = document.querySelector('form[data-feedback]');
    if (!form) {
        return;
    }
    const message = form.elements.namedItem('message');
    const page = form.elements.namedItem('page');
    const count = form.querySelector('[data-feedback-count]');
    const status = form.querySelector('[data-feedback-status]');
    const button = form.querySelector('button[type="submit"]');
    const done = document.querySelector('[data-feedback-done]');

    // Where the visitor was: the link that brought them names it (?from=), else the referrer on this site.
    if (page && !page.value && document.referrer) {
        try {
            const from = new URL(document.referrer);
            if (from.origin === location.origin && from.pathname !== location.pathname) {
                page.value = from.pathname + from.search;
            }
        } catch (e) { /* not a URL: leave it blank */
        }
    }

    const counted = () => {
        if (count && message) {
            count.textContent = message.value.length + ' / ' + message.maxLength;
        }
    };
    counted();
    message?.addEventListener('input', counted);

    // aria-invalid follows :user-invalid, so a screen reader hears an error when the eye sees one.
    const sync = (field) => {
        if (!field.matches?.('input, textarea')) {
            return;
        }
        let invalid;
        try {
            invalid = field.matches(':user-invalid');
        } catch (e) {
            invalid = field.dataset.touched === 'yes' && !field.checkValidity();
        }
        if (invalid) {
            field.setAttribute('aria-invalid', 'true');
        } else {
            field.removeAttribute('aria-invalid');
        }
    };
    form.addEventListener('blur', (e) => {
        e.target.dataset && (e.target.dataset.touched = 'yes');
        sync(e.target);
    }, true);
    form.addEventListener('input', (e) => {
        if (e.target.getAttribute?.('aria-invalid') === 'true') {
            sync(e.target);
        }
    });
    form.addEventListener('invalid', (e) => {
        e.target.dataset && (e.target.dataset.touched = 'yes');
        e.target.setAttribute('aria-invalid', 'true');
    }, true);

    const say = (text, failed) => {
        if (status) {
            status.textContent = text;
            status.classList.toggle('feedback-status-failed', !!failed);
        }
    };

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!form.checkValidity()) {
            form.reportValidity();
            return;
        }
        button && (button.disabled = true);
        say('Sending…', false);
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                headers: {'Accept': 'application/json'},
                body: new URLSearchParams(new FormData(form)),
                credentials: 'same-origin'
            });
            let answer = {};
            try {
                answer = await response.json();
            } catch (err) { /* not JSON: a login page or a proxy's error */
            }
            if (response.ok && answer.sent) {
                form.hidden = true;
                if (done) {
                    done.hidden = false;
                    done.focus();
                } else {
                    say('Thank you: your feedback has been sent.', false);
                }
                return;
            }
            say(answer.error || 'Your feedback could not be sent just now. Please try again in a few minutes.', true);
        } catch (err) {
            say('Your feedback could not be sent: check your connection and try again.', true);
        }
        button && (button.disabled = false);
    });
})();
