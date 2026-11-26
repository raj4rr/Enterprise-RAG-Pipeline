document.addEventListener('DOMContentLoaded', () => {
    // --- State ---
    let selectedFile = null;
    let isProcessing = false;

    // --- Tab Navigation ---
    const tabButtons = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    tabButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const targetTab = btn.getAttribute('data-tab');

            tabButtons.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.remove('active'));

            btn.classList.add('active');
            const targetEl = document.getElementById(targetTab);
            if (targetEl) targetEl.classList.add('active');

            if (targetTab === 'tab-vectordb') {
                loadStatusAndDocuments();
            }
        });
    });

    // --- Slider & Input Bindings ---
    const chunkSizeSlider = document.getElementById('chunkSize');
    const chunkSizeVal = document.getElementById('chunkSizeVal');
    if (chunkSizeSlider && chunkSizeVal) {
        chunkSizeSlider.addEventListener('input', (e) => {
            chunkSizeVal.textContent = e.target.value;
            const overlapSlider = document.getElementById('chunkOverlap');
            if (overlapSlider && parseInt(overlapSlider.value) >= parseInt(e.target.value)) {
                overlapSlider.value = Math.max(0, parseInt(e.target.value) - 50);
                document.getElementById('chunkOverlapVal').textContent = overlapSlider.value;
            }
        });
    }

    const chunkOverlapSlider = document.getElementById('chunkOverlap');
    const chunkOverlapVal = document.getElementById('chunkOverlapVal');
    if (chunkOverlapSlider && chunkOverlapVal) {
        chunkOverlapSlider.addEventListener('input', (e) => {
            chunkOverlapVal.textContent = e.target.value;
        });
    }

    // --- Dropzone & File Input ---
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('fileInput');
    const selectedFileDisplay = document.getElementById('selectedFileDisplay');
    const selectedFileName = document.getElementById('selectedFileName');

    if (dropzone && fileInput) {
        dropzone.addEventListener('click', () => fileInput.click());

        dropzone.addEventListener('dragover', (e) => {
            e.preventDefault();
            dropzone.classList.add('dragover');
        });

        dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));

        dropzone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropzone.classList.remove('dragover');
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                handleFileSelect(e.dataTransfer.files[0]);
            }
        });

        fileInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                handleFileSelect(e.target.files[0]);
            }
        });
    }

    function handleFileSelect(file) {
        selectedFile = file;
        if (selectedFileDisplay && selectedFileName) {
            selectedFileName.textContent = `${file.name} (${formatBytes(file.size)})`;
            selectedFileDisplay.style.display = 'inline-flex';
        }
    }

    // --- Preview Chunks & Quality ---
    const btnPreview = document.getElementById('btnPreview');
    const previewContainer = document.getElementById('previewContainer');
    const previewCountBadge = document.getElementById('previewCountBadge');
    const qualityPillContainer = document.getElementById('qualityPillContainer');

    if (btnPreview) {
        btnPreview.addEventListener('click', async () => {
            const textInput = document.getElementById('rawTextInput').value.trim();
            const chunkSize = parseInt(document.getElementById('chunkSize').value);
            const chunkOverlap = parseInt(document.getElementById('chunkOverlap').value);
            const strategy = document.getElementById('chunkStrategy').value;

            let previewText = textInput;
            if (!previewText && selectedFile) {
                if (selectedFile.type.startsWith('text/') || selectedFile.name.endsWith('.md') || selectedFile.name.endsWith('.txt')) {
                    previewText = await selectedFile.text();
                } else {
                    alert("Preview for binary formats (PDF, DOCX) will be generated upon Ingestion.");
                    return;
                }
            }

            if (!previewText) {
                alert("Please select a text file or enter sample text into the input box to preview chunks.");
                return;
            }

            try {
                btnPreview.disabled = true;
                btnPreview.textContent = 'Analyzing & Previewing...';

                const res = await fetch('/api/rag/preview-chunks', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text: previewText,
                        chunkSize: chunkSize,
                        chunkOverlap: chunkOverlap,
                        strategy: strategy
                    })
                });

                const data = await res.json();
                renderChunks(data.chunks, data.totalChunks, data.totalCharacters);
            } catch (err) {
                alert("Error generating preview: " + err.message);
            } finally {
                btnPreview.disabled = false;
                btnPreview.textContent = '👁️ Preview Chunks';
            }
        });
    }

    function renderChunks(chunks, totalChunks, totalChars, qualityReport) {
        if (!previewContainer) return;
        previewContainer.innerHTML = '';

        if (previewCountBadge) {
            previewCountBadge.textContent = `${totalChunks} chunks (${totalChars} chars)`;
            previewCountBadge.style.display = 'inline-block';
        }

        if (qualityReport && qualityPillContainer) {
            const statusClass = qualityReport.qualityScore >= 0.70 ? 'success' : 'amber';
            qualityPillContainer.innerHTML = `
                <div style="background: rgba(17, 22, 34, 0.8); border: 1px solid var(--border-color); border-radius: var(--radius-sm); padding: 10px 14px; font-size: 0.8rem; display: flex; justify-content: space-between; align-items: center;">
                    <div>
                        <strong>Quality Score:</strong> <span style="color: var(--accent-emerald);">${(qualityReport.qualityScore * 100).toFixed(0)}%</span>
                        • Status: <span class="badge ${statusClass}">${qualityReport.status}</span>
                        • Lexical Diversity: ${(qualityReport.lexicalDiversity * 100).toFixed(1)}%
                    </div>
                    ${qualityReport.extractedTitle ? `<span style="color: var(--text-dim); max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">Title: ${escapeHtml(qualityReport.extractedTitle)}</span>` : ''}
                </div>
            `;
            qualityPillContainer.style.display = 'block';
        }

        chunks.forEach((c) => {
            const card = document.createElement('div');
            card.className = 'chunk-card';
            card.innerHTML = `
                <div class="chunk-header">
                    <span class="chunk-badge">Chunk #${c.index + 1}</span>
                    <span>${c.charCount} chars | ${c.wordCount} words ${c.overlapWithPrevious > 0 ? `| Overlap: ${c.overlapWithPrevious} chars` : ''}</span>
                </div>
                <div class="chunk-text">${escapeHtml(c.content)}</div>
            `;
            previewContainer.appendChild(card);
        });
    }

    // --- Ingestion Handler ---
    const btnIngest = document.getElementById('btnIngest');
    const ingestStatusBanner = document.getElementById('ingestStatusBanner');

    if (btnIngest) {
        btnIngest.addEventListener('click', async () => {
            const chunkSize = parseInt(document.getElementById('chunkSize').value);
            const chunkOverlap = parseInt(document.getElementById('chunkOverlap').value);
            const strategy = document.getElementById('chunkStrategy').value;
            const rawText = document.getElementById('rawTextInput').value.trim();

            if (!selectedFile && !rawText) {
                alert("Please upload a file or enter text to ingest.");
                return;
            }

            try {
                btnIngest.disabled = true;
                btnIngest.innerHTML = 'Sanitizing, Embedding & Ingesting...';
                if (ingestStatusBanner) ingestStatusBanner.style.display = 'none';

                let res, data;
                if (selectedFile) {
                    const formData = new FormData();
                    formData.append('file', selectedFile);
                    formData.append('chunkSize', chunkSize);
                    formData.append('chunkOverlap', chunkOverlap);
                    formData.append('strategy', strategy);

                    res = await fetch('/api/rag/upload', {
                        method: 'POST',
                        body: formData
                    });
                } else {
                    res = await fetch('/api/rag/ingest-text', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            title: 'Note_' + new Date().toISOString().slice(0, 19).replace('T', '_'),
                            content: rawText,
                            chunkSize: chunkSize,
                            chunkOverlap: chunkOverlap,
                            strategy: strategy
                        })
                    });
                }

                data = await res.json();
                if (data.success) {
                    if (ingestStatusBanner) {
                        ingestStatusBanner.className = 'status-banner success';
                        ingestStatusBanner.innerHTML = `✅ <strong>Success!</strong> ${data.message} (${data.elapsedTimeMs} ms)`;
                        ingestStatusBanner.style.display = 'flex';
                    }
                    if (data.sampleChunks && data.sampleChunks.length > 0) {
                        renderChunks(data.sampleChunks, data.totalChunks, data.totalCharacters, data.qualityReport);
                    }
                    loadStatusAndDocuments();
                } else {
                    throw new Error(data.message || 'Ingestion failed');
                }
            } catch (err) {
                if (ingestStatusBanner) {
                    ingestStatusBanner.className = 'status-banner';
                    ingestStatusBanner.style.background = 'rgba(244, 63, 94, 0.15)';
                    ingestStatusBanner.style.color = '#fb7185';
                    ingestStatusBanner.innerHTML = `❌ <strong>Error:</strong> ${err.message}`;
                    ingestStatusBanner.style.display = 'flex';
                }
            } finally {
                btnIngest.disabled = false;
                btnIngest.innerHTML = '⚡ Clean, Ingest & Index';
            }
        });
    }

    // --- Vector DB Status & Reset ---
    const btnResetVector = document.getElementById('btnResetVector');
    if (btnResetVector) {
        btnResetVector.addEventListener('click', async () => {
            if (!confirm("Are you sure you want to clear the Vector DB and BM25 index? All stored embeddings will be removed.")) return;
            try {
                const res = await fetch('/api/rag/reset', { method: 'POST' });
                const data = await res.json();
                alert(data.message);
                loadStatusAndDocuments();
            } catch (e) {
                alert("Error resetting vector store: " + e.message);
            }
        });
    }

    // --- Hybrid Search & Reranking Test ---
    const btnSearchTest = document.getElementById('btnSearchTest');
    const searchResultsContainer = document.getElementById('searchResultsContainer');
    if (btnSearchTest) {
        btnSearchTest.addEventListener('click', async () => {
            const query = document.getElementById('searchQuery').value.trim();
            const topK = document.getElementById('searchTopK').value;
            const threshold = document.getElementById('searchThreshold').value;
            const sourceFilter = document.getElementById('searchSourceFilter').value.trim();
            const rerank = document.getElementById('toggleRerank').checked;

            if (!query) {
                alert("Please enter a search query.");
                return;
            }

            try {
                btnSearchTest.disabled = true;
                btnSearchTest.textContent = 'Executing Hybrid Search & Reranker...';

                let url = `/api/rag/search?query=${encodeURIComponent(query)}&topK=${topK}&threshold=${threshold}&rerank=${rerank}`;
                if (sourceFilter) {
                    url += `&source=${encodeURIComponent(sourceFilter)}`;
                }

                const res = await fetch(url, { method: 'POST' });
                const sources = await res.json();

                if (searchResultsContainer) {
                    searchResultsContainer.innerHTML = '';
                    if (sources.length === 0) {
                        searchResultsContainer.innerHTML = '<div style="color: var(--text-muted); font-size: 0.85rem;">No chunks matched the search threshold or filter criteria.</div>';
                        return;
                    }
                    sources.forEach(s => {
                        const div = document.createElement('div');
                        div.className = 'chunk-card';
                        const matchTypeBadge = s.matchType ? `<span class="badge purple" style="font-size: 0.65rem;">${s.matchType}</span>` : '';
                        div.innerHTML = `
                            <div class="chunk-header">
                                <span class="chunk-badge">Rank #${s.rank} | ${escapeHtml(s.sourceName)}</span>
                                <div style="display: flex; gap: 8px; align-items: center;">
                                    ${matchTypeBadge}
                                    <span class="source-score" style="color: var(--accent-emerald);">Rerank: ${(s.rerankScore * 100).toFixed(1)}%</span>
                                    <span style="color: var(--text-dim); font-size: 0.75rem;">(Initial: ${(s.similarityScore * 100).toFixed(0)}%)</span>
                                </div>
                            </div>
                            <div class="chunk-text">${escapeHtml(s.contentSnippet)}</div>
                        `;
                        searchResultsContainer.appendChild(div);
                    });
                }
            } catch (err) {
                alert("Search error: " + err.message);
            } finally {
                btnSearchTest.disabled = false;
                btnSearchTest.textContent = 'Run Hybrid Search';
            }
        });
    }

    // --- Chatbot Functionality ---
    const chatInput = document.getElementById('chatInput');
    const btnSendMessage = document.getElementById('btnSendMessage');
    const chatMessages = document.getElementById('chatMessages');

    if (btnSendMessage && chatInput) {
        btnSendMessage.addEventListener('click', () => sendChatMessage());
        chatInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendChatMessage();
            }
        });
    }

    document.querySelectorAll('.pill-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            if (chatInput) {
                chatInput.value = btn.textContent;
                sendChatMessage();
            }
        });
    });

    async function sendChatMessage() {
        const query = chatInput.value.trim();
        if (!query || isProcessing) return;

        appendMessage('user', query);
        chatInput.value = '';
        isProcessing = true;
        btnSendMessage.disabled = true;

        const placeholderId = 'msg-' + Date.now();
        appendMessage('assistant', '<span class="typing">Searching Hybrid Index & Synthesizing Grounded Answer...</span>', null, placeholderId);

        try {
            const res = await fetch('/api/rag/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    query: query,
                    topK: 4,
                    similarityThreshold: 0.45,
                    strictGrounding: true,
                    requireCitations: true
                })
            });

            const data = await res.json();
            updateAssistantMessage(placeholderId, data);
        } catch (err) {
            updateAssistantMessage(placeholderId, {
                answer: "Error connecting to RAG service: " + err.message,
                sources: [],
                citations: [],
                confidenceLevel: 'LOW',
                confidenceScore: 0.0,
                isRefusal: false,
                elapsedTimeMs: 0
            });
        } finally {
            isProcessing = false;
            btnSendMessage.disabled = false;
            chatInput.focus();
        }
    }

    function appendMessage(sender, text, sources, elementId) {
        if (!chatMessages) return;
        const msgDiv = document.createElement('div');
        msgDiv.className = `message ${sender}`;
        if (elementId) msgDiv.id = elementId;

        const icon = sender === 'user' ? '👤' : '🤖';
        msgDiv.innerHTML = `
            <div class="avatar ${sender}">${icon}</div>
            <div class="bubble">
                <div class="message-content">${text}</div>
            </div>
        `;
        chatMessages.appendChild(msgDiv);
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    function updateAssistantMessage(elementId, chatData) {
        const msgDiv = document.getElementById(elementId);
        if (!msgDiv) return;

        const bubble = msgDiv.querySelector('.bubble');
        const contentEl = msgDiv.querySelector('.message-content');
        if (contentEl) {
            contentEl.innerHTML = formatMarkdown(chatData.answer);
        }

        // Render Confidence Level Header
        if (chatData.confidenceLevel) {
            let confClass = 'success';
            if (chatData.confidenceLevel === 'MEDIUM') confClass = 'amber';
            if (chatData.confidenceLevel === 'LOW' || chatData.confidenceLevel === 'INSUFFICIENT_CONTEXT') confClass = 'rose';

            const confBar = document.createElement('div');
            confBar.style.display = 'flex';
            confBar.style.alignItems = 'center';
            confBar.style.gap = '8px';
            confBar.style.marginBottom = '8px';
            confBar.style.fontSize = '0.72rem';

            confBar.innerHTML = `
                <span class="badge ${confClass}">● ${chatData.confidenceLevel} CONFIDENCE (${(chatData.confidenceScore * 100).toFixed(0)}%)</span>
                ${chatData.isRefusal ? '<span class="badge rose">🛡️ Zero-Hallucination Safe Refusal</span>' : ''}
            `;
            bubble.insertBefore(confBar, contentEl);
        }

        // Render Verified Citations if present
        if (chatData.citations && chatData.citations.length > 0) {
            const citationsContainer = document.createElement('div');
            citationsContainer.style.marginTop = '10px';
            citationsContainer.style.paddingTop = '8px';
            citationsContainer.style.borderTop = '1px solid rgba(255,255,255,0.08)';

            let citHtml = '<div style="font-size: 0.72rem; color: var(--text-dim); margin-bottom: 4px;">Verified Source Citations:</div><div style="display: flex; flex-wrap: wrap; gap: 6px;">';
            chatData.citations.forEach(c => {
                const icon = c.verified ? '✓' : '⚠️';
                const color = c.verified ? 'var(--accent-emerald)' : 'var(--accent-amber)';
                citHtml += `
                    <span class="citation-pill" title="${escapeHtml(c.supportingSnippet || c.verificationMessage)}" style="border: 1px solid ${color}; color: ${color}; padding: 3px 8px; border-radius: 9999px; font-size: 0.72rem; cursor: pointer;">
                        ${icon} ${escapeHtml(c.sourceName)} (#${c.chunkIndex})
                    </span>
                `;
            });
            citHtml += '</div>';
            citationsContainer.innerHTML = citHtml;
            bubble.appendChild(citationsContainer);
        }

        // Render Retrieved Context Accordion
        if (chatData.sources && chatData.sources.length > 0) {
            const sourcesBox = document.createElement('div');
            sourcesBox.className = 'sources-box';

            let sourcesHtml = `
                <button class="sources-toggle" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'flex' : 'none'">
                    📚 Retrieved Context (${chatData.sources.length} chunks) • ${chatData.elapsedTimeMs}ms ▾
                </button>
                <div class="sources-list" style="display: none;">
            `;

            chatData.sources.forEach(s => {
                sourcesHtml += `
                    <div class="source-item">
                        <div class="source-item-header">
                            <strong>${escapeHtml(s.sourceName)}</strong> (Chunk #${s.chunkIndex + 1})
                            <span class="source-score" style="color: var(--accent-emerald);">Score: ${(s.rerankScore * 100).toFixed(0)}%</span>
                        </div>
                        <div style="color: var(--text-dim); font-size: 0.74rem;">${escapeHtml(s.contentSnippet.substring(0, 180))}...</div>
                    </div>
                `;
            });

            sourcesHtml += `</div>`;
            sourcesBox.innerHTML = sourcesHtml;
            bubble.appendChild(sourcesBox);
        }

        if (chatMessages) chatMessages.scrollTop = chatMessages.scrollHeight;
    }

    // --- TAB 4: Evaluation Benchmark Handler ---
    const btnRunBenchmark = document.getElementById('btnRunBenchmark');
    const benchmarkLoading = document.getElementById('benchmarkLoading');
    const benchmarkResults = document.getElementById('benchmarkResults');
    const benchmarkEmptyPrompt = document.getElementById('benchmarkEmptyPrompt');

    if (btnRunBenchmark) {
        btnRunBenchmark.addEventListener('click', async () => {
            try {
                btnRunBenchmark.disabled = true;
                if (benchmarkLoading) benchmarkLoading.style.display = 'block';
                if (benchmarkEmptyPrompt) benchmarkEmptyPrompt.style.display = 'none';
                if (benchmarkResults) benchmarkResults.style.display = 'none';

                const res = await fetch('/api/rag/evaluate/benchmark');
                const report = await res.json();

                // Populate scorecard
                document.getElementById('metricOverallHealth').textContent = `${(report.overallPipelineHealthScore * 100).toFixed(1)}%`;
                document.getElementById('metricHitRate').textContent = `${(report.averageHitRate * 100).toFixed(0)}%`;
                document.getElementById('metricRecall').textContent = `${(report.averageRecallAtK * 100).toFixed(0)}%`;
                document.getElementById('metricMRR').textContent = report.averageMRR.toFixed(2);
                document.getElementById('metricContextRel').textContent = `${(report.averageContextRelevance * 100).toFixed(1)}%`;

                document.getElementById('metricFaithfulness').textContent = `${(report.averageFaithfulness * 100).toFixed(0)}%`;
                document.getElementById('metricAnswerRel').textContent = `${(report.averageAnswerRelevance * 100).toFixed(0)}%`;
                document.getElementById('metricCitationPrec').textContent = `${(report.averageCitationPrecision * 100).toFixed(0)}%`;
                const halloc = ((1.0 - report.averageFaithfulness) * 100).toFixed(1);
                document.getElementById('metricHallucination').textContent = `${halloc}%`;

                // Render breakdown
                const breakdownContainer = document.getElementById('benchmarkCaseBreakdown');
                if (breakdownContainer && report.testSummaries) {
                    breakdownContainer.innerHTML = '';
                    report.testSummaries.forEach((s, idx) => {
                        const div = document.createElement('div');
                        div.style.background = 'rgba(10, 13, 20, 0.4)';
                        div.style.border = '1px solid var(--border-color)';
                        div.style.borderRadius = 'var(--radius-sm)';
                        div.style.padding = '10px';
                        div.style.fontSize = '0.78rem';
                        div.innerHTML = `
                            <div style="font-weight: 600; color: var(--text-main); margin-bottom: 4px;">Q${idx + 1}: ${escapeHtml(s.query)}</div>
                            <div style="display: flex; gap: 12px; color: var(--text-dim);">
                                <span>Hit: <strong style="color: var(--accent-cyan);">${(s.hitRate * 100).toFixed(0)}%</strong></span>
                                <span>MRR: <strong>${s.mrr.toFixed(2)}</strong></span>
                                <span>Faithfulness: <strong style="color: var(--accent-emerald);">${(s.faithfulness * 100).toFixed(0)}%</strong></span>
                                <span>Citation Prec: <strong>${(s.citationPrecision * 100).toFixed(0)}%</strong></span>
                            </div>
                        `;
                        breakdownContainer.appendChild(div);
                    });
                }

                if (benchmarkLoading) benchmarkLoading.style.display = 'none';
                if (benchmarkResults) benchmarkResults.style.display = 'block';
            } catch (err) {
                alert("Benchmark error: " + err.message);
                if (benchmarkLoading) benchmarkLoading.style.display = 'none';
                if (benchmarkEmptyPrompt) benchmarkEmptyPrompt.style.display = 'block';
            } finally {
                btnRunBenchmark.disabled = false;
            }
        });
    }

    // --- Custom Query Evaluator ---
    const btnRunCustomEval = document.getElementById('btnRunCustomEval');
    const customEvalResult = document.getElementById('customEvalResult');
    if (btnRunCustomEval) {
        btnRunCustomEval.addEventListener('click', async () => {
            const query = document.getElementById('evalCustomQuery').value.trim();
            const keywordsInput = document.getElementById('evalCustomKeywords').value.trim();

            if (!query) {
                alert("Please enter a test query to evaluate.");
                return;
            }

            const keywords = keywordsInput ? keywordsInput.split(',').map(s => s.trim()).filter(Boolean) : [];

            try {
                btnRunCustomEval.disabled = true;
                btnRunCustomEval.textContent = 'Evaluating Retrieval...';

                const res = await fetch('/api/rag/evaluate/retrieval', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        query: query,
                        keywords: keywords,
                        topK: 4
                    })
                });

                const retData = await res.json();
                if (customEvalResult) {
                    customEvalResult.innerHTML = `
                        <div style="background: rgba(10, 13, 20, 0.5); border: 1px solid var(--border-color); border-radius: var(--radius-sm); padding: 12px;">
                            <div style="font-weight: 700; color: var(--accent-cyan); margin-bottom: 6px;">Retrieval Evaluation Results:</div>
                            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 6px; font-size: 0.8rem;">
                                <div>Hit Rate: <strong>${(retData.hitRate * 100).toFixed(0)}%</strong></div>
                                <div>MRR: <strong>${retData.mrr.toFixed(2)}</strong></div>
                                <div>Precision@K: <strong>${(retData.precisionAtK * 100).toFixed(0)}%</strong></div>
                                <div>Context Relevance: <strong>${(retData.contextRelevanceScore * 100).toFixed(1)}%</strong></div>
                            </div>
                            <div style="font-size: 0.75rem; color: var(--text-dim); margin-top: 6px;">
                                Retrieved Sources: ${retData.retrievedSources.join(', ') || 'None'}
                            </div>
                        </div>
                    `;
                    customEvalResult.style.display = 'block';
                }
            } catch (err) {
                alert("Evaluation error: " + err.message);
            } finally {
                btnRunCustomEval.disabled = false;
                btnRunCustomEval.textContent = 'Evaluate Retrieval & Context';
            }
        });
    }

    // --- Helper & Status Functions ---
    async function loadStatusAndDocuments() {
        try {
            const statusRes = await fetch('/api/rag/status');
            const status = await statusRes.json();

            const docCountBadge = document.getElementById('headerDocCount');
            const chunkCountBadge = document.getElementById('headerChunkCount');
            if (chunkCountBadge) {
                chunkCountBadge.textContent = `${status.totalStoredChunks} chunks`;
            }

            const infoEmbeddingModel = document.getElementById('infoEmbeddingModel');
            if (infoEmbeddingModel && status.embeddingModel) {
                infoEmbeddingModel.textContent = status.embeddingModel;
            }

            const docsRes = await fetch('/api/rag/documents');
            const docsData = await docsRes.json();
            const docsList = Array.isArray(docsData) ? docsData : (docsData.files || []);

            if (docCountBadge) {
                docCountBadge.textContent = `${docsList.length} files`;
            }

            const storedDocsList = document.getElementById('storedDocsList');
            if (storedDocsList) {
                storedDocsList.innerHTML = '';
                if (docsList.length === 0) {
                    storedDocsList.innerHTML = '<li style="color: var(--text-dim); padding: 12px 0;">No documents uploaded yet.</li>';
                } else {
                    docsList.forEach(doc => {
                        const name = typeof doc === 'string' ? doc : doc.filename;
                        const chunks = doc.chunkCount !== undefined ? doc.chunkCount : '-';
                        const size = doc.fileSizeBytes && doc.fileSizeBytes > 0 ? ` • ${formatBytes(doc.fileSizeBytes)}` : '';

                        const li = document.createElement('li');
                        li.style.display = 'flex';
                        li.style.justifyContent = 'space-between';
                        li.style.alignItems = 'center';
                        li.style.padding = '8px 0';
                        li.style.borderBottom = '1px solid rgba(255,255,255,0.06)';
                        li.style.gap = '8px';

                        const infoDiv = document.createElement('div');
                        infoDiv.style.overflow = 'hidden';
                        infoDiv.style.textOverflow = 'ellipsis';
                        infoDiv.style.whiteSpace = 'nowrap';
                        infoDiv.innerHTML = `<span>📄 <strong>${escapeHtml(name)}</strong></span> <span style="color: var(--text-dim); font-size: 0.75rem; margin-left: 6px;">(${chunks} chunks${size})</span>`;

                        const deleteBtn = document.createElement('button');
                        deleteBtn.innerHTML = '🗑️ Delete';
                        deleteBtn.title = `Delete ${name} and its vectors`;
                        deleteBtn.className = 'btn-icon-danger';
                        deleteBtn.onclick = async (e) => {
                            e.stopPropagation();
                            if (confirm(`Delete '${name}' and remove its chunks from the vector database?`)) {
                                try {
                                    deleteBtn.disabled = true;
                                    deleteBtn.textContent = 'Deleting...';
                                    await fetch(`/api/rag/documents/${encodeURIComponent(name)}`, { method: 'DELETE' });
                                    await loadStatusAndDocuments();
                                } catch (err) {
                                    alert(`Failed to delete document: ${err.message}`);
                                }
                            }
                        };

                        li.appendChild(infoDiv);
                        li.appendChild(deleteBtn);
                        storedDocsList.appendChild(li);
                    });
                }
            }
        } catch (e) {
            console.warn("Could not fetch status:", e);
        }
    }

    function formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }

    function escapeHtml(text) {
        if (!text) return '';
        const map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' };
        return text.replace(/[&<>"']/g, m => map[m]);
    }

    function formatMarkdown(text) {
        if (!text) return '';
        let html = escapeHtml(text);
        html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/^\s*-\s+(.*)$/gm, '• $1');
        html = html.replace(/\n/g, '<br/>');
        return html;
    }

    // Initial load
    loadStatusAndDocuments();
});
