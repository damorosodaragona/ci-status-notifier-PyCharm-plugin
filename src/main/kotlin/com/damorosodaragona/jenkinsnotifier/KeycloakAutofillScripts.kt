package com.damorosodaragona.jenkinsnotifier

internal object KeycloakAutofillScripts {
    fun backgroundScript(
        baseUrl: String,
        username: String,
        password: String,
        successInject: String,
        failureInject: String,
        logInject: String,
        reason: String,
    ): String {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val probeUrl = "$normalizedBaseUrl/whoAmI/api/json"
        return """
            (function() {
              function log(msg) { try { $logInject } catch (e) {} }
              function ok(msg) { try { $successInject } catch (e) {} }
              function fail(msg) { try { $failureInject } catch (e) {} }
              var current = window.location.href || '';
              var form = document.querySelector('#kc-form-login');
              var user = document.querySelector('#username');
              var pass = document.querySelector('#password');
              var btn = document.querySelector('#kc-login');
              var alreadySubmitted = window.__ciStatusKeycloakSubmitted === true;
              var probeRunning = window.__ciStatusProbeRunning === true;
              log('reason=${jsString(reason)} url=' + current + ' ready=' + document.readyState + ' form=' + !!form + ' user=' + !!user + ' pass=' + !!pass + ' btn=' + !!btn + ' submitted=' + alreadySubmitted + ' probeRunning=' + probeRunning);

              if (form && user && pass && !alreadySubmitted) {
                window.__ciStatusKeycloakSubmitted = true;
                try {
                  user.focus();
                  user.value = '${jsString(username)}';
                  user.dispatchEvent(new Event('input', { bubbles: true }));
                  user.dispatchEvent(new Event('change', { bubbles: true }));
                  pass.focus();
                  pass.value = '${jsString(password)}';
                  pass.dispatchEvent(new Event('input', { bubbles: true }));
                  pass.dispatchEvent(new Event('change', { bubbles: true }));
                  log('form filled; scheduling submit');
                  setTimeout(function() {
                    try {
                      if (btn) { btn.click(); log('clicked #kc-login'); }
                      else { form.submit(); log('submitted form'); }
                    } catch (e) { fail('submit-error=' + e); }
                  }, 250);
                } catch (e) {
                  fail('fill-error=' + e);
                }
                return;
              }

              if (form && alreadySubmitted) {
                log('form still present but already submitted; waiting for redirect');
                return;
              }

              if (probeRunning) {
                log('probe already running; waiting');
                return;
              }

              if (current.startsWith('${jsString(normalizedBaseUrl)}') && current.indexOf('commenceLogin') === -1) {
                window.__ciStatusProbeRunning = true;
                log('on Jenkins origin; starting API probe url=${jsString(probeUrl)}');
                fetch('${jsString(probeUrl)}', {
                  credentials: 'include',
                  cache: 'no-store',
                  headers: { 'Accept': 'application/json' }
                }).then(function(r) {
                  window.__ciStatusProbeRunning = false;
                  log('probe status=' + r.status + ' url=' + r.url);
                  if (r.ok) ok('status=' + r.status + ' url=' + r.url);
                  else fail('status=' + r.status + ' url=' + r.url);
                }).catch(function(e) {
                  window.__ciStatusProbeRunning = false;
                  fail('probe-error=' + e);
                });
              } else {
                log('not Jenkins origin or login-like URL; waiting');
              }
            })();
        """.trimIndent()
    }

    fun interactiveAutofillScript(
        username: String,
        password: String,
        logInject: String,
        reason: String,
    ): String = """
        (function() {
          function log(msg) { try { $logInject } catch (e) {} }
          var current = window.location.href || '';
          var form = document.querySelector('#kc-form-login');
          var user = document.querySelector('#username');
          var pass = document.querySelector('#password');
          var filled = window.__ciStatusKeycloakInteractiveFilled === true;
          log('reason=${jsString(reason)} url=' + current + ' ready=' + document.readyState + ' form=' + !!form + ' user=' + !!user + ' pass=' + !!pass + ' filled=' + filled);
          if (form && user && pass && !filled) {
            window.__ciStatusKeycloakInteractiveFilled = true;
            try {
              user.focus();
              user.value = '${jsString(username)}';
              user.dispatchEvent(new Event('input', { bubbles: true }));
              user.dispatchEvent(new Event('change', { bubbles: true }));
              pass.focus();
              pass.value = '${jsString(password)}';
              pass.dispatchEvent(new Event('input', { bubbles: true }));
              pass.dispatchEvent(new Event('change', { bubbles: true }));
              log('credentials filled; waiting for user submit');
            } catch (e) {
              log('fill-error=' + e);
            }
          }
        })();
    """.trimIndent()

    fun jsString(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
}
