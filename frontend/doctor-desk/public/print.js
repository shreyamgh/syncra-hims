// Bill print page: opened by the desk at /print.html#<billId>. Browsers copy sessionStorage into
// tabs a page opens, so the signed-in user's token is available here. The bill's HTML (every value
// escaped by the billing service) replaces this page; this page's CSP allows its inline styles but
// no inline scripts.
(function () {
  window.opener = null;
  var billId = decodeURIComponent(location.hash.slice(1));
  var token = null;
  try { token = sessionStorage.getItem('syncra.session'); } catch (e) { /* storage blocked */ }
  function fail(message) {
    document.body.textContent = message;
  }
  if (!/^[0-9a-f-]{36}$/.test(billId)) return fail('No bill to print.');
  if (!token) return fail('Sign in to the desk, then print the bill again.');
  fetch('/api/v1/billing/bills/' + billId + '/print', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'text/html' }, credentials: 'omit',
  }).then(function (response) {
    if (!response.ok) throw new Error('The bill could not be printed (' + response.status + ').');
    return response.text();
  }).then(function (html) {
    document.open();
    document.write(html);
    document.close();
    setTimeout(function () { window.print(); }, 300);
  }).catch(function (error) { fail(error.message); });
})();
