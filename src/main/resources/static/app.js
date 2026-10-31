(() => {
  'use strict';

  const byId = (id) => document.getElementById(id);
  const state = {
    package: null,
    query: '',
    resultPage: 0,
    pageSize: 20,
    selectedPage: 1,
    hits: [],
    maxResultCount: 5000,
    searchRequestId: 0,
    searchController: null,
    pageRequestId: 0,
  };

  async function api(path, options = {}) {
    const response = await fetch(path, { ...options, headers: { Accept: 'application/json' } });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.message || `Request failed (${response.status})`);
    return payload;
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (character) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[character]);
  }

  function queryTerms(query) {
    return [...query.matchAll(/"([^"]+)"|([\p{L}\p{N}*]+)/gu)]
      .map((match) => (match[1] || match[2] || '').replace(/\*$/, ''))
      .filter((term) => term.length > 1 && !['and', 'or', 'not'].includes(term.toLowerCase()));
  }

  function highlightSnippet(snippet, query) {
    let html = escapeHtml(snippet);
    const terms = [...new Set(queryTerms(query))].sort((left, right) => right.length - left.length);
    for (const term of terms) {
      const safeTerm = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      html = html.replace(new RegExp(`(${safeTerm})`, 'ig'), '<mark>$1</mark>');
    }
    return html;
  }

  function setStatus(message, stateName = 'ready') {
    byId('status-text').textContent = message;
    byId('status-pill').className = `status-pill is-${stateName}`;
  }

  function selectTab(tabName) {
    const isResults = tabName === 'results';
    byId('results-tab').classList.toggle('is-active', isResults);
    byId('documents-tab').classList.toggle('is-active', !isResults);
    byId('results-tab').setAttribute('aria-selected', String(isResults));
    byId('documents-tab').setAttribute('aria-selected', String(!isResults));
    byId('results-tab').tabIndex = isResults ? 0 : -1;
    byId('documents-tab').tabIndex = isResults ? -1 : 0;
    byId('results-panel').hidden = !isResults;
    byId('documents-panel').hidden = isResults;
  }

  function resetSearchResults() {
    state.query = '';
    state.hits = [];
    state.resultPage = 0;
    byId('results').setAttribute('aria-busy', 'false');
    byId('tab-result-count').textContent = '0';
    byId('results-heading').textContent = 'Ready when you are';
    byId('result-range').textContent = 'Search the entire loan package to find evidence.';
    byId('results').innerHTML = '<div class="rail-empty"><div class="empty-icon">⌕</div><strong>Start with a document detail</strong><p>Search for a borrower, amount, form, disclosure, or phrase to find the source page.</p></div>';
    byId('pagination').hidden = true;
    byId('performance-panel').hidden = true;
  }

  function renderPackageDetails(details) {
    const manifest = details.manifest;
    state.package = details;
    byId('package-title').textContent = manifest.packageName;
    byId('package-subtitle').textContent = `Loan ${manifest.loanId} · ${manifest.borrowerLabel} · ${manifest.documents.length} document entries`;
    byId('stat-pages').textContent = manifest.pageCount.toLocaleString();
    byId('stat-documents').textContent = manifest.documents.length.toLocaleString();
    byId('stat-missing').textContent = details.missingTextPages.toLocaleString();
    byId('stat-indexed').textContent = details.indexedTerms.toLocaleString();
    byId('page-total').textContent = `/ ${manifest.pageCount}`;
    byId('page-number').max = String(manifest.pageCount);
    byId('tab-document-count').textContent = manifest.documents.length.toLocaleString();
    byId('results-badge').textContent = `${manifest.pageCount.toLocaleString()} PAGES`;
    byId('document-summary').textContent = `${manifest.documents.length} documents across ${new Set(manifest.documents.map((doc) => doc.type)).size} categories`;
    renderDocumentTree(manifest.documents);

    const pdfAvailable = details.pdfAvailable;
    byId('pdf-empty').hidden = pdfAvailable;
    byId('pdf-frame').hidden = !pdfAvailable;
    if (pdfAvailable) byId('pdf-frame').src = `${details.pdfUrl}#page=1&view=FitH`;
    setStatus(pdfAvailable ? 'Package ready' : 'Text-only review', 'ready');
    return showPage(1, false);
  }

  function renderDocumentTree(documents) {
    const groups = new Map();
    [...documents].sort((a, b) => a.startPage - b.startPage).forEach((document) => {
      if (!groups.has(document.type)) groups.set(document.type, []);
      groups.get(document.type).push(document);
    });

    const markup = [...groups.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([type, docs], index) => {
      const totalPages = docs.reduce((sum, document) => sum + document.pageCount, 0);
      const rows = docs.map((document) => `
        <div class="tree-doc">
          <button type="button" data-start-page="${document.startPage}" title="Open ${escapeHtml(document.title)} at PDF page ${document.startPage}">
            <span class="doc-icon" aria-hidden="true">▤</span>
            <span class="doc-copy">
              <span class="doc-title">${escapeHtml(document.title)}</span>
              <span class="doc-meta">Version ${escapeHtml(document.version)} · ${document.pageCount} page${document.pageCount === 1 ? '' : 's'}</span>
            </span>
            <span class="doc-pages">${document.startPage}–${document.endPage}</span>
          </button>
        </div>`).join('');
      return `
        <details class="tree-group" ${index === 0 ? 'open' : ''} data-type="${escapeHtml(type.toLowerCase())}">
          <summary><span class="group-name">${escapeHtml(type)}</span><span class="group-count">${docs.length} docs · ${totalPages} pp.</span></summary>
          ${rows}
        </details>`;
    }).join('');

    byId('document-tree').innerHTML = markup || '<div class="rail-empty">No documents are configured in this loan package.</div>';
    byId('document-tree').querySelectorAll('[data-start-page]').forEach((button) => {
      button.addEventListener('click', () => showPage(Number(button.dataset.startPage)));
    });
  }

  function renderTiming(response) {
    const timing = response.timings;
    const items = [
      ['Cache lookup', timing.cacheLookupMs],
      ['Query parsing', timing.parseMs],
      ['Matching pages', timing.evaluationMs],
      ['Relevance ranking', timing.rankingMs],
      ['Snippet creation', timing.snippetMs],
      ['Search total', timing.totalMs],
      ['Manifest read', response.manifestReadMs],
      ['Layout read', response.layoutReadMs],
      ['Page mapping', response.pageMappingMs],
      ['Index build', response.indexBuildMs],
      ['Package build', response.totalBuildMs],
    ];
    byId('timing-row').innerHTML = items.map(([label, value]) => `
      <div class="timing-item"><span>${label}</span><strong>${Number(value).toFixed(2)} ms</strong></div>`).join('');
    byId('performance-mode').textContent = timing.cacheHit ? 'CACHED' : 'FIRST PASS';
    byId('performance-panel').hidden = false;
  }

  function renderResults(response) {
    state.hits = response.hits || [];
    state.resultPage = response.page;
    byId('tab-result-count').textContent = response.totalHits.toLocaleString();
    byId('results-heading').textContent = response.totalHits
      ? `${response.totalHits.toLocaleString()} matching pages`
      : 'No matching pages';
    const first = response.totalHits ? response.page * response.size + 1 : 0;
    const last = Math.min((response.page + 1) * response.size, response.totalHits, state.maxResultCount);
    byId('result-range').textContent = response.totalHits
      ? `Showing ${first.toLocaleString()}–${last.toLocaleString()} of ${Math.min(response.totalHits, state.maxResultCount).toLocaleString()} results`
      : 'Try another term, an exact phrase, or a broader Boolean query.';

    if (!state.hits.length) {
      byId('results').innerHTML = '<div class="rail-empty"><div class="empty-icon">⌕</div><strong>No matching pages</strong><p>Check the spelling or broaden the search using OR. Pages without extracted text cannot match text queries.</p></div>';
    } else {
      byId('results').innerHTML = state.hits.map((hit, index) => `
        <button class="result-card ${state.selectedPage === hit.pageNumber ? 'is-selected' : ''}" type="button" data-result-index="${index}">
          <span class="result-card-top">
            <span class="result-card-title">${escapeHtml(hit.documentTitle)}</span>
            <span class="page-pill">PAGE ${hit.pageNumber}</span>
          </span>
          <span class="result-card-meta">${escapeHtml(hit.documentType)} · Version ${escapeHtml(hit.version)} · Relevance ${hit.score.toFixed(3)}</span>
          <span class="result-card-snippet">${highlightSnippet(hit.snippet, state.query)}</span>
          ${hit.textMissing ? '<span class="missing-label">Text extraction unavailable · open source</span>' : ''}
        </button>`).join('');
      byId('results').querySelectorAll('[data-result-index]').forEach((button) => {
        button.addEventListener('click', () => {
          const hit = state.hits[Number(button.dataset.resultIndex)];
          showPage(hit.pageNumber);
          byId('results').querySelectorAll('.result-card').forEach((card) => card.classList.remove('is-selected'));
          button.classList.add('is-selected');
        });
      });
    }

    const totalPages = Math.max(1, Math.ceil(Math.min(response.totalHits, state.maxResultCount) / response.size));
    byId('pagination').hidden = response.totalHits <= response.size;
    byId('page-label').textContent = `Page ${response.page + 1} of ${totalPages}`;
    byId('prev-page').disabled = response.page === 0;
    byId('next-page').disabled = (response.page + 1) * response.size >= Math.min(response.totalHits, state.maxResultCount);
    renderTiming(response);
  }

  async function runSearch(nextPage = 0) {
    const query = byId('query').value.trim();
    if (!query) return;
    const requestId = ++state.searchRequestId;
    state.searchController?.abort();
    state.searchController = new AbortController();
    state.query = query;
    byId('search-button').disabled = true;
    byId('search-button').textContent = 'Searching…';
    byId('clear-search').hidden = false;
    selectTab('results');
    byId('results').setAttribute('aria-busy', 'true');
    byId('results').innerHTML = '<div class="loading-line">Searching extracted text across the loan package…</div>';
    try {
      const response = await api(`/api/search?q=${encodeURIComponent(query)}&page=${nextPage}&size=${state.pageSize}`, {
        signal: state.searchController.signal,
      });
      if (requestId !== state.searchRequestId) return;
      state.resultPage = nextPage;
      renderResults(response);
    } catch (error) {
      if (requestId !== state.searchRequestId) return;
      byId('results').innerHTML = `<div class="rail-empty"><strong>Search could not be completed</strong><p>${escapeHtml(error.message)}</p></div>`;
      byId('results').setAttribute('aria-busy', 'false');
      state.hits = [];
      byId('tab-result-count').textContent = '0';
      byId('results-heading').textContent = 'Search error';
      byId('result-range').textContent = 'Review the query and try again.';
      byId('pagination').hidden = true;
      byId('performance-panel').hidden = true;
    } finally {
      if (requestId === state.searchRequestId) {
        state.searchController = null;
        byId('search-button').disabled = false;
        byId('search-button').textContent = 'Search';
      }
    }
  }

  function cancelSearch() {
    state.searchRequestId++;
    state.searchController?.abort();
    state.searchController = null;
    byId('search-button').disabled = false;
    byId('search-button').textContent = 'Search';
  }

  function clearSearch() {
    byId('query').value = '';
    byId('clear-search').hidden = true;
    cancelSearch();
    resetSearchResults();
    byId('query').focus();
  }

  function openPageFromInput() {
    const input = byId('page-number');
    const pageNumber = Number(input.value);
    if (!Number.isInteger(pageNumber) || !state.package
        || pageNumber < 1 || pageNumber > state.package.manifest.pageCount) {
      byId('text-status').textContent = `Enter a page from 1 to ${state.package?.manifest.pageCount ?? '—'}.`;
      input.focus();
      return;
    }
    showPage(pageNumber);
  }

  async function showPage(pageNumber, updatePdf = true) {
    if (!state.package || !Number.isInteger(pageNumber)
        || pageNumber < 1 || pageNumber > state.package.manifest.pageCount) return;
    state.selectedPage = pageNumber;
    const requestId = ++state.pageRequestId;
    byId('page-number').value = String(pageNumber);
    if (updatePdf && state.package.pdfAvailable) {
      byId('pdf-frame').src = `${state.package.pdfUrl}#page=${pageNumber}&view=FitH`;
    }
    byId('page-text').textContent = 'Loading source evidence…';
    byId('text-status').textContent = 'Loading page';
    try {
      const page = await api(`/api/pages/${pageNumber}`);
      if (requestId !== state.pageRequestId) return;
      byId('viewer-title').textContent = page.documentTitle;
      byId('viewer-subtitle').textContent = `Loan ${state.package.manifest.loanId} · Physical PDF page ${page.pageNumber}`;
      byId('viewer-document-type').textContent = page.documentType.toUpperCase();
      byId('viewer-version').textContent = `VERSION ${page.version}`;
      byId('page-text').textContent = page.textMissing
        ? 'No extracted text is available for this physical page. The page remains part of the package and can still be reviewed in the original PDF when it is attached.'
        : page.text;
      byId('text-status').textContent = page.textMissing ? 'Text extraction unavailable' : 'Extracted source text';
      byId('evidence-length').textContent = page.textMissing ? 'NO TEXT LAYER' : `${page.text.length.toLocaleString()} CHARACTERS`;
      byId('page-prev').disabled = pageNumber <= 1;
      byId('page-next').disabled = pageNumber >= state.package.manifest.pageCount;
    } catch (error) {
      if (requestId !== state.pageRequestId) return;
      byId('text-status').textContent = error.message;
      byId('page-text').textContent = 'Unable to load this page. Check the API response and try again.';
    }
  }

  byId('search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    runSearch(0);
  });
  byId('query').addEventListener('input', () => {
    const isEmpty = byId('query').value.length === 0;
    byId('clear-search').hidden = isEmpty;
    if (isEmpty) {
      cancelSearch();
      resetSearchResults();
    }
  });
  byId('clear-search').addEventListener('click', clearSearch);
  document.querySelectorAll('[data-query]').forEach((button) => {
    button.addEventListener('click', () => {
      byId('query').value = button.dataset.query;
      byId('clear-search').hidden = false;
      runSearch(0);
    });
  });
  document.querySelectorAll('[data-tab]').forEach((button) => {
    button.addEventListener('click', () => selectTab(button.dataset.tab));
  });
  byId('results-tab').parentElement.addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const tabs = [byId('results-tab'), byId('documents-tab')];
    const nextIndex = event.key === 'Home' ? 0
      : event.key === 'End' ? tabs.length - 1
        : (tabs.indexOf(document.activeElement) + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    tabs[nextIndex].focus();
    selectTab(tabs[nextIndex].dataset.tab);
  });
  byId('prev-page').addEventListener('click', () => runSearch(Math.max(0, state.resultPage - 1)));
  byId('next-page').addEventListener('click', () => runSearch(state.resultPage + 1));
  byId('go-page').addEventListener('click', openPageFromInput);
  byId('page-number').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') openPageFromInput();
  });
  byId('page-prev').addEventListener('click', () => showPage(state.selectedPage - 1));
  byId('page-next').addEventListener('click', () => showPage(state.selectedPage + 1));
  byId('document-filter').addEventListener('input', (event) => {
    const query = event.target.value.trim().toLowerCase();
    byId('document-tree').querySelectorAll('.tree-group').forEach((group) => {
      let visibleDocs = 0;
      const matchesType = group.dataset.type.includes(query);
      group.querySelectorAll('.tree-doc').forEach((row) => {
        const visible = !query || matchesType || row.textContent.toLowerCase().includes(query);
        row.hidden = !visible;
        if (visible) visibleDocs++;
      });
      group.hidden = query ? visibleDocs === 0 : false;
      if (query && !group.hidden) group.open = true;
    });
  });

  selectTab('results');
  api('/api/package')
    .then(renderPackageDetails)
    .catch((error) => {
      setStatus('Package unavailable', 'error');
      byId('package-title').textContent = 'Unable to load loan package';
      byId('package-subtitle').textContent = error.message;
      byId('document-tree').innerHTML = `<div class="loading-line">${escapeHtml(error.message)}</div>`;
    });
})();
