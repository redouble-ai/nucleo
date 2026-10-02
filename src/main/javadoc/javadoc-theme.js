/*
 * The System/Light/Dark theme switch for the generated javadoc, loaded into every page's
 * head via --add-script. javadoc-theme.css carries the palettes: the system choice rides
 * prefers-color-scheme, an explicit choice sets data-theme on the document element and is
 * stored under the same key the documentation site and the demo page use, so one choice
 * follows the reader across all three.
 */
(function() {
    // Before first paint: a stored theme choice must win over the system preference without a flash.
    var stored = localStorage.getItem('nucleo-theme');
    if (stored) {
        document.documentElement.setAttribute('data-theme', stored);
    }
    document.addEventListener('DOMContentLoaded', function() {
        var host = document.querySelector('div.top-nav .nav-content');
        if (!host) {
            return;
        }
        var brand = document.createElement('a');
        brand.className = 'rd-brand';
        brand.href = 'https://redouble.ai';
        brand.setAttribute('aria-label', 'Redouble AI');
        // The Redouble AI lockup (gradient mark + white wordmark), inlined because the
        // javadoc tree carries no image assets of its own
        brand.innerHTML = '<svg viewBox="0 0 159 31" height="18" fill="none" xmlns="http://www.w3.org/2000/svg">'
                + '<path d="M15.292 9.20117C16.3958 11.113 15.7409 13.5583 13.8291 14.6621L12.0596 15.6836C10.8052 16.408 10.3625 17.9985 11.0508 19.2637L11.084 19.3232L17.7256 30.8262C16.9988 30.9307 16.2557 30.9863 15.5 30.9863C6.93957 30.9862 0 24.0468 0 15.4863C7.15883e-05 8.69187 4.37202 2.91905 10.4561 0.826172L15.292 9.20117ZM16.1328 0C24.3999 0.331896 30.9999 7.13781 31 15.4863C31 21.3707 27.7211 26.4891 22.8906 29.1143L17.0225 18.9482C21.0719 16.2405 22.3781 10.8169 19.9072 6.53711L16.1328 0Z" fill="url(#logoGradHero)"></path>'
                + '<path d="M39.5846 21.6523V8.44698H45.6214C47.3004 8.44698 48.5832 8.89973 49.4698 9.78638C50.2244 10.541 50.6017 11.5408 50.6017 12.8047V12.8425C50.6017 14.9742 49.4509 16.3136 47.772 16.9361L50.9979 21.6523H47.6022L44.7725 17.4266H42.4898V21.6523H39.5846ZM42.4898 14.861H45.4327C46.8665 14.861 47.6588 14.1253 47.6588 12.9934V12.9557C47.6588 11.6917 46.8099 11.0692 45.3761 11.0692H42.4898V14.861ZM57.7117 21.8787C54.6744 21.8787 52.4295 19.747 52.4295 16.6532V16.6154C52.4295 13.7291 54.4858 11.3522 57.4287 11.3522C60.8055 11.3522 62.3524 13.9744 62.3524 16.8418C62.3524 17.0682 62.3335 17.3134 62.3147 17.5964H55.2781C55.5611 18.8981 56.4666 19.5772 57.7494 19.5772C58.7115 19.5772 59.3906 19.2754 60.2018 18.5397L61.8431 19.9922C60.881 21.1807 59.5415 21.8787 57.7117 21.8787ZM55.2404 15.8043H59.5604C59.3906 14.5214 58.636 13.6537 57.4287 13.6537C56.2213 13.6537 55.4668 14.5026 55.2404 15.8043ZM68.8283 21.841C66.4702 21.841 64.2253 19.9922 64.2253 16.6154V16.5777C64.2253 13.2009 66.4325 11.3522 68.8283 11.3522C70.3564 11.3522 71.2996 12.0502 71.9788 12.8613V7.88103H74.8462V21.6523H71.9788V20.1998C71.2808 21.1619 70.3375 21.841 68.8283 21.841ZM69.5641 19.4074C70.9035 19.4074 72.0165 18.2944 72.0165 16.6154V16.5777C72.0165 14.8987 70.9035 13.7857 69.5641 13.7857C68.2247 13.7857 67.0928 14.8799 67.0928 16.5777V16.6154C67.0928 18.2944 68.2247 19.4074 69.5641 19.4074ZM82.5582 21.8787C79.4455 21.8787 77.1063 19.5772 77.1063 16.6532V16.6154C77.1063 13.7103 79.4455 11.3522 82.596 11.3522C85.7087 11.3522 88.0479 13.6537 88.0479 16.5777V16.6154C88.0479 19.5206 85.7087 21.8787 82.5582 21.8787ZM82.596 19.4074C84.2372 19.4074 85.2182 18.1624 85.2182 16.6532V16.6154C85.2182 15.1251 84.1429 13.8234 82.5582 13.8234C80.917 13.8234 79.936 15.0685 79.936 16.5777V16.6154C79.936 18.1058 81.0113 19.4074 82.596 19.4074ZM93.6708 21.841C91.4825 21.841 90.2374 20.4261 90.2374 18.0869V11.5408H93.1048V17.1814C93.1048 18.5397 93.7274 19.2376 94.8404 19.2376C95.9534 19.2376 96.6326 18.5397 96.6326 17.1814V11.5408H99.5V21.6523H96.6326V20.2186C95.9723 21.0675 95.1045 21.841 93.6708 21.841ZM108.273 21.841C106.745 21.841 105.802 21.143 105.123 20.3318V21.6523H102.255V7.88103H105.123V12.9934C105.821 12.0313 106.764 11.3522 108.273 11.3522C110.631 11.3522 112.876 13.2009 112.876 16.5777V16.6154C112.876 19.9922 110.669 21.841 108.273 21.841ZM107.537 19.4074C108.877 19.4074 110.009 18.3133 110.009 16.6154V16.5777C110.009 14.8987 108.877 13.7857 107.537 13.7857C106.198 13.7857 105.085 14.8987 105.085 16.5777V16.6154C105.085 18.2944 106.198 19.4074 107.537 19.4074ZM115.306 21.6523V7.88103H118.173V21.6523H115.306ZM125.847 21.8787C122.81 21.8787 120.565 19.747 120.565 16.6532V16.6154C120.565 13.7291 122.621 11.3522 125.564 11.3522C128.941 11.3522 130.488 13.9744 130.488 16.8418C130.488 17.0682 130.469 17.3134 130.45 17.5964H123.413C123.696 18.8981 124.602 19.5772 125.885 19.5772C126.847 19.5772 127.526 19.2754 128.337 18.5397L129.978 19.9922C129.016 21.1807 127.677 21.8787 125.847 21.8787ZM123.375 15.8043H127.696C127.526 14.5214 126.771 13.6537 125.564 13.6537C124.356 13.6537 123.602 14.5026 123.375 15.8043ZM137.828 21.6523L143.487 8.35265H146.166L151.825 21.6523H148.788L147.581 18.6906H141.997L140.789 21.6523H137.828ZM143.034 16.125H146.543L144.789 11.8426L143.034 16.125ZM154.184 21.6523V8.44698H157.089V21.6523H154.184Z" fill="white"></path>'
                + '<defs><linearGradient id="logoGradHero" x1="7.61197" y1="5.24957" x2="22.3107" y2="34.6473" gradientUnits="userSpaceOnUse">'
                + '<stop stop-color="#B251F3"></stop><stop offset="0.706731" stop-color="#336DFF"></stop></linearGradient></defs></svg>';
        host.insertBefore(brand, host.firstChild);
        var box = document.createElement('div');
        box.className = 'theme-switch';
        var buttons = ['system', 'light', 'dark'].map(function(mode) {
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = mode.charAt(0).toUpperCase() + mode.slice(1);
            button.setAttribute('data-mode', mode);
            box.appendChild(button);
            return button;
        });
        function applyTheme(mode) {
            if (mode === 'light' || mode === 'dark') {
                document.documentElement.setAttribute('data-theme', mode);
                localStorage.setItem('nucleo-theme', mode);
            } else {
                document.documentElement.removeAttribute('data-theme');
                localStorage.removeItem('nucleo-theme');
            }
            buttons.forEach(function(b) {
                b.classList.toggle('active', b.getAttribute('data-mode') === (mode || 'system'));
            });
        }
        buttons.forEach(function(b) {
            b.addEventListener('click', function() { applyTheme(b.getAttribute('data-mode')); });
        });
        host.appendChild(box);
        applyTheme(localStorage.getItem('nucleo-theme') || 'system');
    });
})();
