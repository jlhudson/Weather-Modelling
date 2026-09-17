// Light and dark, remembered per browser. The first paint is settled by the inline script in
// layout.html's head; this file only handles changing it afterwards and keeping the label honest.
//
// Three states, not two. "auto" is not a starting value that collapses into light or dark on first
// click - it is a standing instruction to follow the operating system, including when the operator's
// machine switches itself at sunset.
(function () {
    var KEY = 'weather.theme';
    var root = document.documentElement;
    var system = window.matchMedia('(prefers-color-scheme: dark)');

    function choice() {
        return root.getAttribute('data-hub-theme') || 'auto';
    }

    function apply(next) {
        var dark = next === 'dark' || (next === 'auto' && system.matches);
        root.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
        root.setAttribute('data-hub-theme', next);
        label(next);
        // Anything drawn rather than styled - the map's own canvas - needs telling.
        document.dispatchEvent(new CustomEvent('hub:theme', {detail: {choice: next, dark: dark}}));
    }

    function label(next) {
        var text = next === 'auto' ? 'System' : next === 'dark' ? 'Dark' : 'Light';
        document.querySelectorAll('[data-hub-theme-label]').forEach(function (el) {
            el.textContent = text;
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        label(choice());
        document.querySelectorAll('[data-hub-set-theme]').forEach(function (button) {
            button.addEventListener('click', function () {
                var next = button.getAttribute('data-hub-set-theme');
                try {
                    localStorage.setItem(KEY, next);
                } catch (e) {
                    // Storage blocked: the choice still applies to this page, it just will not persist.
                }
                apply(next);
            });
        });
    });

    // Only while following the system. An explicit choice is not overridden by the OS changing.
    system.addEventListener('change', function () {
        if (choice() === 'auto') {
            apply('auto');
        }
    });
})();
