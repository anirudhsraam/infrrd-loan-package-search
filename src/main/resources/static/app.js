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
    evidenceText: '',
    evidenceMessage: 'Choose a page from the search results or document index. Source text will appear here with its physical page reference.',
    evidenceModeOriginal: false,
    evidenceMatchIndex: 0,
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

  function parseEvidenceRows(text) {
    const normalizedContext = String(text ?? '').replace(/\s+/g, ' ');
    const moneyPattern = /-?\$?(?:\d{1,3}(?:,\s?\d{3})+|\d+)\.\d{2}/g;
    const amountsIn = (line) => [...line.matchAll(moneyPattern)];
    const splitColumns = (line) => {
      const cells = [];
      const starts = [];
      let start = 0;
      for (const separator of line.matchAll(/[ \t]{3,}/g)) {
        const segment = line.slice(start, separator.index);
        const value = segment.trim();
        if (value) {
          cells.push(value.replace(/[ \t]+/g, ' '));
          starts.push(start + segment.indexOf(value));
        }
        start = separator.index + separator[0].length;
      }
      const segment = line.slice(start);
      const value = segment.trim();
      if (value) {
        cells.push(value.replace(/[ \t]+/g, ' '));
        starts.push(start + segment.indexOf(value));
      }
      return { cells, starts };
    };
    const lines = String(text ?? '').replace(/\r\n?/g, '\n').split('\n')
      .map((line) => line.replace(/\s+$/, ''))
      .filter((line) => line.trim())
      .map((line) => ({ raw: line, ...splitColumns(line) }));
    const blocks = [];
    let index = 0;

    while (index < lines.length) {
      const header = lines[index];
      const headerLabels = header.cells.map((label) => label.toLowerCase().replace(/[^a-z]+/g, ' ').trim());
      const accountActivity = /\bstatement of account\b/i.test(normalizedContext)
        && headerLabels.join('|') === 'date|description|withdrawals|deposits|balance';
      const payrollTable = /\b(?:earnings statement|statement of earnings|pay stub)\b/i.test(normalizedContext)
        && (headerLabels.join('|') === 'description|hours|rate|current|ytd'
          || headerLabels.join('|') === 'description|current|ytd');
      const tableKind = accountActivity ? 'account-activity' : payrollTable ? 'payroll' : '';
      const isDataRow = (line) => {
        if (line.cells.length < 2 || line.cells.length > headerLabels.length) return false;
        if (tableKind === 'account-activity') {
          return /^\s*\d{1,2}\/\d{1,2}\/\d{4}\b/.test(line.raw)
            && amountsIn(line.raw).length >= 2;
        }
        return Boolean(tableKind)
          && amountsIn(line.raw).length >= 2;
      };
      const nextLines = lines.slice(index + 1, index + 4);
      if (tableKind && nextLines.filter(isDataRow).length >= 2) {
        const starts = header.starts.slice(0, headerLabels.length);
        const rows = [];
        let dataIndex = index + 1;
        while (dataIndex < lines.length) {
          const current = lines[dataIndex];
          if (!isDataRow(current)) break;
          const values = Array(headerLabels.length).fill('');
          if (tableKind === 'account-activity') {
            const date = current.raw.match(/^\s*(\d{1,2}\/\d{1,2}\/\d{4})\b/);
            const amounts = amountsIn(current.raw);
            values[0] = date?.[1] ?? '';
            values[1] = current.raw.slice((date?.[0].length ?? 0), amounts[0]?.index ?? current.raw.length).trim().replace(/[ \t]+/g, ' ');
            assignAmounts(values, amounts, starts, 2);
          } else {
            const amounts = amountsIn(current.raw);
            values[0] = current.raw.slice(0, amounts[0]?.index ?? current.raw.length).trim().replace(/[ \t]+/g, ' ');
            assignAmounts(values, amounts, starts, 1);
          }
          if (values.filter(Boolean).length < 2) break;
          rows.push(values);
          dataIndex++;
        }
        if (rows.length >= 2) {
          blocks.push({ type: 'table', kind: tableKind, headers: header.cells, rows });
          index = dataIndex;
          continue;
        }
      }

      blocks.push({ type: 'text', text: header.cells.join(' · ') });
      index++;
    }
    return blocks;
  }

  function assignAmounts(values, amounts, columnStarts, firstNumericColumn) {
    for (const amount of amounts) {
      let closestColumn = firstNumericColumn;
      let closestDistance = Infinity;
      for (let column = firstNumericColumn; column < columnStarts.length; column++) {
        const distance = Math.abs(amount.index - columnStarts[column]);
        if (distance < closestDistance) {
          closestColumn = column;
          closestDistance = distance;
        }
      }
      values[closestColumn] = amount[0].trim().replace(/,\s+/g, ',');
    }
  }

  function evidenceSearchText() {
    if (state.evidenceModeOriginal) return state.evidenceText;
    return parseEvidenceRows(state.evidenceText).map((block) => block.type === 'table'
      ? [block.headers, ...block.rows].flat().join(' ')
      : block.text).join(' ');
  }

  function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function renderEvidenceContent(container) {
    const query = byId('evidence-find').value.trim();
    let matchIndex = 0;
    const renderCell = (value) => {
      if (!query) return escapeHtml(value);
      const segments = value.split(new RegExp(`(${escapeRegExp(query)})`, 'ig'));
      return segments.map((segment, index) => {
        const safeSegment = escapeHtml(segment);
        if (index % 2 === 0) return safeSegment;
        const activeClass = matchIndex++ === state.evidenceMatchIndex ? ' class="is-current-match"' : '';
        return `<mark${activeClass}>${safeSegment}</mark>`;
      }).join('');
    };

    if (!state.evidenceText) {
      container.innerHTML = `<p class="evidence-message">${escapeHtml(state.evidenceMessage)}</p>`;
    } else if (state.evidenceModeOriginal) {
      container.textContent = state.evidenceText;
      if (query) {
        const text = container.textContent;
        const parts = text.split(new RegExp(`(${escapeRegExp(query)})`, 'ig'));
        container.innerHTML = parts.map((part, index) => {
          const safePart = escapeHtml(part);
          if (index % 2 === 0) return safePart;
          const activeClass = matchIndex++ === state.evidenceMatchIndex ? ' class="is-current-match"' : '';
          return `<mark${activeClass}>${safePart}</mark>`;
        }).join('');
      }
    } else {
        const blocks = parseEvidenceRows(state.evidenceText);
        container.innerHTML = blocks.map((block) => {
          if (block.type === 'text') {
            return `<p class="evidence-page-line">${renderCell(block.text)}</p>`;
          }
          return `
            <div class="evidence-table-wrap" data-table-kind="${block.kind}" style="--column-count: ${block.headers.length}">
              <table class="evidence-table">
                <caption class="sr-only">${block.kind === 'account-activity' ? 'Bank account activity transactions' : 'Payroll earnings and deductions'}</caption>
                <thead><tr>${block.headers.map((header) => `<th scope="col" class="${isNumericColumn(header) ? 'is-numeric' : ''}">${renderCell(header)}</th>`).join('')}</tr></thead>
                <tbody>${block.rows.map((row) => `
                  <tr>${block.headers.map((header, columnIndex) => {
                    const value = row[columnIndex] ?? '';
                    const classes = [isNumericColumn(header) ? 'is-numeric' : '', value ? '' : 'empty-cell'].filter(Boolean).join(' ');
                    return `<td class="${classes}">${renderCell(value)}</td>`;
                  }).join('')}</tr>
                `).join('')}</tbody>
              </table>
            </div>`;
        }).join('');
    }
    container.classList.toggle('is-original', state.evidenceModeOriginal);
    return matchIndex;
  }

  function isNumericColumn(header) {
    return /\b(?:hours?|rate|current|ytd|withdrawals?|deposits?|balance)\b/i.test(header);
  }

  function renderEvidence() {
    const query = byId('evidence-find').value.trim();
    const text = evidenceSearchText();
    const count = query ? (text.split(new RegExp(`(${escapeRegExp(query)})`, 'ig')).length - 1) / 2 : 0;
    if (count) state.evidenceMatchIndex = Math.min(state.evidenceMatchIndex, count - 1);
    else state.evidenceMatchIndex = 0;
    renderEvidenceContent(byId('page-text'));
    const dialogCount = renderEvidenceContent(byId('expanded-page-text'));
    const hasText = Boolean(state.evidenceText);
    byId('evidence-length').textContent = hasText
      ? state.evidenceModeOriginal
        ? `${state.evidenceText.length.toLocaleString()} characters · original`
        : `${parseEvidenceRows(state.evidenceText).length.toLocaleString()} page sections`
      : (state.evidenceMessage.startsWith('No text is available') ? 'TEXT UNAVAILABLE' : '—');
    byId('evidence-mode').textContent = state.evidenceModeOriginal ? 'Show table view' : 'Show original text';
    byId('evidence-mode').setAttribute('aria-pressed', String(state.evidenceModeOriginal));
    byId('evidence-intro').textContent = state.evidenceModeOriginal
      ? 'Original extraction with spacing preserved. This can be difficult to read; select “Show table view” to see recognized tables with their page headings. Text quality is a heuristic, not OCR confidence.'
      : 'Aligned fields are grouped for readability. Tables are used only for clearly identified account activity and payroll rows; form layouts stay in reading order. Text quality is a heuristic, not OCR confidence.';
    byId('evidence-find-count').textContent = query
      ? `${count ? state.evidenceMatchIndex + 1 : 0} of ${count}`
      : '';
    byId('evidence-find').disabled = !hasText;
    byId('evidence-find-prev').disabled = count === 0;
    byId('evidence-find-next').disabled = count === 0;
    byId('evidence-copy').disabled = !hasText;
    byId('evidence-mode').disabled = !hasText;
    if (byId('evidence-dialog').open && dialogCount) {
      const activeMark = byId('expanded-page-text').querySelector('mark.is-current-match');
      activeMark?.scrollIntoView({ block: 'center' });
    }
  }

  function moveEvidenceMatch(direction) {
    const count = [...byId('page-text').querySelectorAll('mark')].length;
    if (!count) return;
    state.evidenceMatchIndex = (state.evidenceMatchIndex + direction + count) % count;
    renderEvidence();
    const container = byId('evidence-dialog').open ? byId('expanded-page-text') : byId('page-text');
    container.querySelector('mark.is-current-match')?.scrollIntoView({ block: 'center' });
  }

  async function copyEvidenceText() {
    const text = state.evidenceText
      ? (state.evidenceModeOriginal
        ? state.evidenceText
        : parseEvidenceRows(state.evidenceText).map((block) => block.type === 'table'
          ? [block.headers.join('\t'), ...block.rows.map((row) => row.join('\t'))].join('\n')
          : block.text).join('\n'))
      : state.evidenceMessage;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        throw new Error('Clipboard access is unavailable');
      }
      byId('evidence-status').textContent = 'Source text copied.';
    } catch {
      const copyField = document.createElement('textarea');
      copyField.value = text;
      copyField.setAttribute('readonly', '');
      copyField.style.position = 'fixed';
      copyField.style.opacity = '0';
      document.body.append(copyField);
      copyField.select();
      const copied = document.execCommand('copy');
      copyField.remove();
      byId('evidence-status').textContent = copied
        ? 'Source text copied.'
        : 'Could not copy the text. Select it and copy using your keyboard.';
    }
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
    byId('stat-low-quality').textContent = details.lowQualityTextPages.toLocaleString();
    byId('page-total').textContent = `/ ${manifest.pageCount}`;
    byId('page-number').max = String(manifest.pageCount);
    byId('tab-document-count').textContent = manifest.documents.length.toLocaleString();
    byId('results-badge').textContent = `${manifest.pageCount.toLocaleString()} PAGES`;
    byId('document-summary').textContent = `${manifest.documents.length} documents across ${new Set(manifest.documents.map((doc) => doc.type)).size} categories`;
    const typeFilter = byId('document-type-filter');
    const selectedType = typeFilter.value;
    typeFilter.replaceChildren(new Option('All document types', ''));
    [...new Set(manifest.documents.map((doc) => doc.type))]
      .sort((left, right) => left.localeCompare(right))
      .forEach((type) => typeFilter.add(new Option(type, type)));
    typeFilter.value = [...typeFilter.options].some((option) => option.value === selectedType) ? selectedType : '';
    typeFilter.disabled = false;
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
    byId('results').setAttribute('aria-busy', 'false');
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
        <button class="result-card ${state.selectedPage === hit.pageNumber ? 'is-selected' : ''}" type="button" data-result-index="${index}" aria-current="${state.selectedPage === hit.pageNumber ? 'page' : 'false'}">
          <span class="result-card-top">
            <span class="result-card-title">${escapeHtml(hit.documentTitle)}</span>
            <span class="page-pill">PAGE ${hit.pageNumber}</span>
          </span>
          <span class="result-card-meta">${escapeHtml(hit.documentType)} · Version ${escapeHtml(hit.version)} · Relevance ${hit.score.toFixed(3)} · ${escapeHtml(textMetadataLabel(hit.textMetadata))}</span>
          <span class="result-card-snippet">${highlightSnippet(hit.snippet, state.query)}</span>
          ${hit.textMissing ? '<span class="missing-label">Text extraction unavailable · open source</span>' : ''}
        </button>`).join('');
      byId('results').querySelectorAll('[data-result-index]').forEach((button) => {
        button.addEventListener('click', () => {
          const hit = state.hits[Number(button.dataset.resultIndex)];
          showPage(hit.pageNumber);
          byId('results').querySelectorAll('.result-card').forEach((card) => {
            const selected = card === button;
            card.classList.toggle('is-selected', selected);
            card.setAttribute('aria-current', selected ? 'page' : 'false');
          });
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
      const documentType = byId('document-type-filter').value;
      const params = new URLSearchParams({ q: query, page: String(nextPage), size: String(state.pageSize) });
      if (documentType) params.set('documentType', documentType);
      const response = await api(`/api/search?${params}`, {
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
    state.evidenceText = '';
    state.evidenceMessage = 'Loading page text…';
    state.evidenceMatchIndex = 0;
    byId('evidence-status').textContent = '';
    renderEvidence();
    byId('text-status').textContent = 'Loading page';
    try {
      const page = await api(`/api/pages/${pageNumber}`);
      if (requestId !== state.pageRequestId) return;
      byId('viewer-title').textContent = page.documentTitle;
      byId('viewer-subtitle').textContent = `Loan ${state.package.manifest.loanId} · Physical PDF page ${page.pageNumber}`;
      byId('viewer-document-type').textContent = page.documentType.toUpperCase();
      byId('viewer-version').textContent = `VERSION ${page.version}`;
      state.evidenceText = page.textMissing ? '' : page.text;
      state.evidenceMessage = page.textMissing
        ? 'No text is available for this page. You can still review the page in the original PDF when it is attached.'
        : 'No readable text was found on this page.';
      state.evidenceMatchIndex = 0;
      byId('evidence-find').value = '';
      byId('evidence-status').textContent = '';
      renderEvidence();
      byId('evidence-dialog-subtitle').textContent = `${page.documentTitle} · PDF page ${page.pageNumber}`;
      byId('text-status').textContent = `${textMetadataLabel(page.textMetadata)} · ${page.textMetadata.nonWhitespaceCharacters.toLocaleString()} non-whitespace characters`;
      byId('text-status').title = 'Quality is a heuristic based on text length, searchable-character ratio, and replacement characters; it is not OCR confidence.';
      byId('page-prev').disabled = pageNumber <= 1;
      byId('page-next').disabled = pageNumber >= state.package.manifest.pageCount;
    } catch (error) {
      if (requestId !== state.pageRequestId) return;
      byId('text-status').textContent = error.message;
      state.evidenceText = '';
      state.evidenceMessage = 'This page text could not be loaded. Please try selecting the page again.';
      renderEvidence();
    }
  }

  function textMetadataLabel(metadata) {
    const sources = {
      LAYOUT: 'provided layout',
      PDF_TEXT: 'PDF text extraction',
      OCR: 'OCR',
      NONE: 'no text source',
    };
    const quality = {
      USABLE: 'usable text',
      LOW: 'low-quality text',
      NO_TEXT: 'no extracted text',
    };
    return `${sources[metadata?.source] || 'unknown source'} · ${quality[metadata?.quality] || 'quality unknown'}`;
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
  byId('document-type-filter').addEventListener('change', () => {
    if (byId('query').value.trim()) runSearch(0);
  });
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
  byId('evidence-find').addEventListener('input', () => {
    state.evidenceMatchIndex = 0;
    renderEvidence();
  });
  byId('evidence-find').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      moveEvidenceMatch(event.shiftKey ? -1 : 1);
    }
  });
  byId('evidence-find-prev').addEventListener('click', () => moveEvidenceMatch(-1));
  byId('evidence-find-next').addEventListener('click', () => moveEvidenceMatch(1));
  byId('evidence-mode').addEventListener('click', () => {
    state.evidenceModeOriginal = !state.evidenceModeOriginal;
    renderEvidence();
  });
  byId('evidence-copy').addEventListener('click', copyEvidenceText);
  byId('evidence-expand').addEventListener('click', () => {
    byId('evidence-dialog').showModal();
    renderEvidence();
    byId('expanded-page-text').focus();
  });
  byId('evidence-dialog-close').addEventListener('click', () => byId('evidence-dialog').close());
  byId('evidence-dialog').addEventListener('click', (event) => {
    if (event.target === byId('evidence-dialog')) byId('evidence-dialog').close();
  });
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
