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

    // --- TAB 4: Evaluation Benchmark & Metric Formulas Registry ---
    const METRIC_DEFINITIONS = {
        overallHealth: {
            title: "Composite RAG Pipeline Health",
            category: "System Quality Index",
            formula: "Health = 0.20·HitRate + 0.15·MRR + 0.15·ContextRel + 0.25·Faithfulness + 0.15·AnswerRel + 0.10·CitationPrec",
            calculation: [
                "Computes a weighted harmonic balance across 6 decoupled evaluation dimensions.",
                "Prioritizes Grounding/Faithfulness (25%) and Retrieval Recall (20%) to prevent hallucinations while maximizing coverage.",
                "Normalizes composite score into a single 0–100% enterprise readiness grade."
            ],
            target: "≥ 80% Enterprise Grade | ≥ 60% Production Ready | < 60% Degraded"
        },
        hitRate: {
            title: "Hit Rate",
            category: "Retrieval Metric",
            formula: "Hit Rate = (1 / |Q|) × ∑ 𝕀(RelevantChunk ∈ Top-K)",
            calculation: [
                "Evaluates whether at least one target ground-truth chunk or expected term is retrieved in top-K.",
                "Returns 1.0 (100%) if any relevant chunk is captured in candidate set, otherwise 0.0 (0%).",
                "Averages across all test queries in the benchmark suite."
            ],
            target: "≥ 90% Optimal | 70–89% Strong | < 70% Poor"
        },
        recall: {
            title: "Recall@K",
            category: "Retrieval Metric",
            formula: "Recall@K = |Retrieved Relevant Chunks in Top-K| / |Total Expected Target Documents|",
            calculation: [
                "Counts total distinct relevant ground-truth document sources surfaced in the top-K window.",
                "Divides captured relevant documents by total expected target documents.",
                "Quantifies whether candidate pool retains all requisite information for answering."
            ],
            target: "≥ 80% Optimal | 60–79% Strong | < 60% Needs Tuning"
        },
        precisionAtK: {
            title: "Precision@K",
            category: "Retrieval Density Metric",
            formula: "Precision@K = |Relevant Chunks in Top-K| / K",
            calculation: [
                "Examines all K candidate chunks returned by dense vector + BM25 hybrid search.",
                "Calculates proportion of retrieved chunks containing expected reference concepts.",
                "Measures signal density: higher precision prevents LLM context window clutter."
            ],
            target: "≥ 75% Optimal | 50–74% Strong | < 50% Low Density"
        },
        mrr: {
            title: "Mean Reciprocal Rank (MRR)",
            category: "Ranking Quality Metric",
            formula: "MRR = (1 / |Q|) × ∑ (1 / Rank of First Relevant Chunk)",
            calculation: [
                "Finds positional rank (1-based index) of the highest-ranked relevant chunk.",
                "Takes reciprocal: Rank #1 → 1/1 = 1.00; Rank #2 → 1/2 = 0.50; Rank #3 → 1/3 = 0.33.",
                "Penalizes retrieval when cross-encoder reranker fails to surface key evidence to top positions."
            ],
            target: "≥ 0.90 1st Rank | ≥ 0.50 Top-2 | < 0.50 Suboptimal"
        },
        contextRel: {
            title: "Context Relevance",
            category: "Retrieval Signal Metric",
            formula: "ContextRel = ∑ count(QueryTerm ∈ Chunks) / (|QueryTerms| × min(K, 2))",
            calculation: [
                "Extracts non-stopword query keywords and domain terminology.",
                "Scans retrieved chunks for density and frequency of query concepts.",
                "Penalizes off-topic chunks to guarantee high signal-to-noise context for generation."
            ],
            target: "≥ 80% High Density | 60–79% Moderate | < 60% Noisy"
        },
        faithfulness: {
            title: "Faithfulness / Grounding",
            category: "Generation Factuality Metric",
            formula: "Faithfulness = |Supported Claims| / |Total Claims in Answer|",
            calculation: [
                "Segments LLM completion into individual propositional sentences.",
                "Cross-examines each claim against retrieved chunks with ≥ 35% lexical/semantic overlap requirement.",
                "Identifies unsupported statements and measures grounded factual precision."
            ],
            target: "≥ 85% Grounded | 65–84% Acceptable | < 65% Hallucination Risk"
        },
        answerRel: {
            title: "Answer Relevance",
            category: "Generation Alignment Metric",
            formula: "AnswerRel = |Salient Query Terms in Answer| / |Total Salient Query Terms|",
            calculation: [
                "Isolates salient non-stopword query tokens representing user intent.",
                "Determines whether generated LLM completion directly addresses query subjects.",
                "Prevents evasive answers, generic disclaimers, or off-topic hallucinations."
            ],
            target: "≥ 80% Direct Answer | 60–79% Partial | < 60% Tangential"
        },
        citationPrec: {
            title: "Citation Precision",
            category: "Generation Verification Metric",
            formula: "Citation Precision = |Verified Inline Citations| / |Total Inline Citations|",
            calculation: [
                "Parses all inline citations matching `[Source: <file>, Chunk #<N>]` regex.",
                "Verifies that each citation points to an actual chunk in the retrieved candidate set.",
                "Confirms document source metadata exists and was not fabricated."
            ],
            target: "100% Fully Verified | < 100% Invalid Citations Detected"
        },
        hallucination: {
            title: "Hallucination Rate",
            category: "Safety & Risk Metric",
            formula: "Hallucination Rate = 1.0 - Faithfulness Score",
            calculation: [
                "Inverts Faithfulness score to measure unsupported factual risk.",
                "Represents percentage of claims that lack verification in retrieved documents.",
                "Zero risk (0.0%) indicates total grounding in ingested context."
            ],
            target: "≤ 10% Minimal Risk | 10–30% Moderate Risk | > 30% High Risk"
        }
    };

    function getMetricGrade(val, type = 'normal') {
        if (type === 'hallucination') {
            if (val <= 0.10) return { label: 'MINIMAL RISK', className: 'metric-grade-optimal', color: 'var(--accent-emerald)', fillClass: 'fill-emerald' };
            if (val <= 0.30) return { label: 'MODERATE RISK', className: 'metric-grade-fair', color: 'var(--accent-amber)', fillClass: 'fill-amber' };
            return { label: 'HIGH RISK', className: 'metric-grade-poor', color: 'var(--accent-rose)', fillClass: 'fill-rose' };
        }
        if (type === 'mrr') {
            if (val >= 0.95) return { label: '1ST RANK', className: 'metric-grade-optimal', color: 'var(--accent-emerald)', fillClass: 'fill-emerald' };
            if (val >= 0.50) return { label: 'TOP 2', className: 'metric-grade-strong', color: 'var(--accent-cyan)', fillClass: 'fill-cyan' };
            if (val >= 0.33) return { label: 'TOP 3', className: 'metric-grade-fair', color: 'var(--accent-amber)', fillClass: 'fill-amber' };
            return { label: 'LOW RANK', className: 'metric-grade-poor', color: 'var(--accent-rose)', fillClass: 'fill-rose' };
        }
        if (val >= 0.85) return { label: 'OPTIMAL', className: 'metric-grade-optimal', color: 'var(--accent-emerald)', fillClass: 'fill-emerald' };
        if (val >= 0.70) return { label: 'STRONG', className: 'metric-grade-strong', color: 'var(--accent-cyan)', fillClass: 'fill-cyan' };
        if (val >= 0.50) return { label: 'FAIR', className: 'metric-grade-fair', color: 'var(--accent-amber)', fillClass: 'fill-amber' };
        return { label: 'POOR', className: 'metric-grade-poor', color: 'var(--accent-rose)', fillClass: 'fill-rose' };
    }

    function updateMetricGui(valElId, gradeElId, barElId, val, type = 'normal', format = '%') {
        const valEl = document.getElementById(valElId);
        const gradeEl = document.getElementById(gradeElId);
        const barEl = document.getElementById(barElId);
        const grade = getMetricGrade(val, type);

        if (valEl) {
            valEl.textContent = format === '%' ? `${(val * 100).toFixed(0)}%` : (format === 'dec%' ? `${(val * 100).toFixed(1)}%` : val.toFixed(2));
            valEl.style.color = grade.color;
        }
        if (gradeEl) {
            gradeEl.textContent = grade.label;
            gradeEl.className = `metric-grade-pill ${grade.className}`;
        }
        if (barEl) {
            const widthPct = type === 'hallucination' ? Math.max(5, (1.0 - val) * 100) : Math.min(100, Math.max(0, val * 100));
            barEl.style.width = `${widthPct}%`;
            barEl.className = `metric-progress-fill ${grade.fillClass}`;
        }
    }

    // --- Floating Metric Formula Tooltip Popover ---
    const tooltipEl = document.getElementById('metricFormulaTooltip');
    let currentPinnedTarget = null;

    function showMetricTooltip(metricKey, targetEl) {
        if (!tooltipEl) return;
        const info = METRIC_DEFINITIONS[metricKey];
        if (!info) return;

        tooltipEl.innerHTML = `
            <div class="popover-header">
                <div class="popover-title">
                    <span>🧮</span>
                    <span>${escapeHtml(info.title)}</span>
                </div>
                <span class="popover-category">${escapeHtml(info.category)}</span>
            </div>
            
            <div class="popover-section-label">Mathematical Formula</div>
            <div class="popover-formula-box">${escapeHtml(info.formula)}</div>
            
            <div class="popover-section-label">How It Is Calculated</div>
            <ul class="popover-calc-list">
                ${info.calculation.map(step => `<li>${escapeHtml(step)}</li>`).join('')}
            </ul>
            
            <div class="popover-target-row">
                <span>🎯 Target:</span>
                <strong>${escapeHtml(info.target)}</strong>
            </div>
        `;

        tooltipEl.style.display = 'block';
        positionTooltip(targetEl);
        // Force reflow for smooth animation
        tooltipEl.offsetHeight;
        tooltipEl.classList.add('visible');
    }

    function hideMetricTooltip() {
        if (!tooltipEl || currentPinnedTarget) return;
        tooltipEl.classList.remove('visible');
        setTimeout(() => {
            if (!tooltipEl.classList.contains('visible')) {
                tooltipEl.style.display = 'none';
            }
        }, 200);
    }

    function positionTooltip(targetEl) {
        if (!tooltipEl) return;
        const rect = targetEl.getBoundingClientRect();
        const tooltipWidth = 390;
        const tooltipHeight = tooltipEl.offsetHeight || 230;
        const padding = 14;

        let left = rect.left + (rect.width / 2) - (tooltipWidth / 2);
        let top = rect.top - tooltipHeight - 10;

        if (left < padding) left = padding;
        if (left + tooltipWidth > window.innerWidth - padding) {
            left = window.innerWidth - tooltipWidth - padding;
        }

        // Flip below if too close to viewport top
        if (top < padding) {
            top = rect.bottom + 10;
        }

        tooltipEl.style.left = `${Math.round(left)}px`;
        tooltipEl.style.top = `${Math.round(top)}px`;
    }

    // Delegated hover & click triggers for metric formula tooltips
    document.addEventListener('mouseover', (e) => {
        if (currentPinnedTarget) return;
        const metricCard = e.target.closest('[data-metric]');
        if (metricCard) {
            const key = metricCard.getAttribute('data-metric');
            showMetricTooltip(key, metricCard);
        }
    });

    document.addEventListener('mouseout', (e) => {
        if (currentPinnedTarget) return;
        const metricCard = e.target.closest('[data-metric]');
        if (metricCard) {
            if (e.relatedTarget && metricCard.contains(e.relatedTarget)) return;
            hideMetricTooltip();
        }
    });

    document.addEventListener('click', (e) => {
        const infoTrigger = e.target.closest('.metric-info-trigger');
        const metricCard = e.target.closest('[data-metric]');

        if (infoTrigger && metricCard) {
            e.stopPropagation();
            const key = metricCard.getAttribute('data-metric');
            if (currentPinnedTarget === metricCard) {
                currentPinnedTarget = null;
                tooltipEl.classList.remove('pinned');
                hideMetricTooltip();
            } else {
                currentPinnedTarget = metricCard;
                showMetricTooltip(key, metricCard);
                tooltipEl.classList.add('pinned');
            }
            return;
        }

        if (currentPinnedTarget && !e.target.closest('.metric-formula-popover')) {
            currentPinnedTarget = null;
            tooltipEl.classList.remove('pinned');
            hideMetricTooltip();
        }
    });

    // --- Benchmark Suite Runner Handler ---
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

                // 1. Overall Pipeline Health Gauge
                const healthScore = report.overallPipelineHealthScore || 0;
                const healthEl = document.getElementById('metricOverallHealth');
                const healthBar = document.getElementById('barOverallHealth');
                const healthBadge = document.getElementById('healthStatusBadge');

                if (healthEl) healthEl.textContent = `${(healthScore * 100).toFixed(1)}%`;
                if (healthBar) {
                    healthBar.style.width = `${Math.min(100, Math.max(0, healthScore * 100)).toFixed(1)}%`;
                    if (healthScore >= 0.75) healthBar.className = 'health-gauge-fill fill-emerald';
                    else if (healthScore >= 0.50) healthBar.className = 'health-gauge-fill fill-cyan';
                    else if (healthScore >= 0.35) healthBar.className = 'health-gauge-fill fill-amber';
                    else healthBar.className = 'health-gauge-fill fill-rose';
                }
                if (healthBadge) {
                    if (healthScore >= 0.80) {
                        healthBadge.textContent = 'ENTERPRISE GRADE';
                        healthBadge.className = 'badge success';
                    } else if (healthScore >= 0.60) {
                        healthBadge.textContent = 'PRODUCTION READY';
                        healthBadge.className = 'badge cyan';
                    } else if (healthScore >= 0.40) {
                        healthBadge.textContent = 'NEEDS TUNING';
                        healthBadge.className = 'badge amber';
                    } else {
                        healthBadge.textContent = 'DEGRADED';
                        healthBadge.className = 'badge rose';
                    }
                }

                // 2. Retrieval Metrics GUI Cards
                updateMetricGui('metricHitRate', 'gradeHitRate', 'barHitRate', report.averageHitRate, 'normal', '%');
                updateMetricGui('metricRecall', 'gradeRecall', 'barRecall', report.averageRecallAtK, 'normal', '%');
                updateMetricGui('metricMRR', 'gradeMRR', 'barMRR', report.averageMRR, 'mrr', 'num');
                updateMetricGui('metricContextRel', 'gradeContextRel', 'barContextRel', report.averageContextRelevance, 'normal', 'dec%');

                // 3. Generation Metrics GUI Cards
                updateMetricGui('metricFaithfulness', 'gradeFaithfulness', 'barFaithfulness', report.averageFaithfulness, 'normal', '%');
                updateMetricGui('metricAnswerRel', 'gradeAnswerRel', 'barAnswerRel', report.averageAnswerRelevance, 'normal', '%');
                updateMetricGui('metricCitationPrec', 'gradeCitationPrec', 'barCitationPrec', report.averageCitationPrecision, 'normal', '%');
                const halloc = Math.max(0, 1.0 - report.averageFaithfulness);
                updateMetricGui('metricHallucination', 'gradeHallucination', 'barHallucination', halloc, 'hallucination', 'dec%');

                // 4. Render Breakdown Cards
                const breakdownContainer = document.getElementById('benchmarkCaseBreakdown');
                if (breakdownContainer && report.testSummaries) {
                    breakdownContainer.innerHTML = '';
                    report.testSummaries.forEach((s, idx) => {
                        const card = document.createElement('div');
                        card.className = 'benchmark-query-card';
                        const hitPassed = s.hitRate >= 0.99;
                        card.innerHTML = `
                            <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 6px;">
                                <div style="font-weight: 600; color: var(--text-main); font-size: 0.82rem;">
                                    <span style="color: var(--accent-primary); margin-right: 6px;">Q${idx + 1}:</span>
                                    ${escapeHtml(s.query)}
                                </div>
                                <span class="badge ${hitPassed ? 'success' : 'amber'}" style="font-size: 0.65rem; padding: 2px 7px;">
                                    ${hitPassed ? 'HIT FOUND' : 'MISS'}
                                </span>
                            </div>
                            <div class="query-metric-chips">
                                <div class="query-chip">
                                    <span style="color: var(--text-dim);">Hit:</span>
                                    <strong style="color: var(--accent-cyan);">${(s.hitRate * 100).toFixed(0)}%</strong>
                                </div>
                                <div class="query-chip">
                                    <span style="color: var(--text-dim);">MRR:</span>
                                    <strong style="color: var(--text-main);">${s.mrr.toFixed(2)}</strong>
                                </div>
                                <div class="query-chip">
                                    <span style="color: var(--text-dim);">Faithfulness:</span>
                                    <strong style="color: var(--accent-emerald);">${(s.faithfulness * 100).toFixed(0)}%</strong>
                                </div>
                                <div class="query-chip">
                                    <span style="color: var(--text-dim);">Citation Prec:</span>
                                    <strong style="color: var(--accent-cyan);">${(s.citationPrecision * 100).toFixed(0)}%</strong>
                                </div>
                            </div>
                        `;
                        breakdownContainer.appendChild(card);
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

    // --- Custom Query Evaluator GUI Handler with Mouseover Formula Support ---
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
                    const hitGrade = getMetricGrade(retData.hitRate, 'normal');
                    const precGrade = getMetricGrade(retData.precisionAtK, 'normal');
                    const mrrGrade = getMetricGrade(retData.mrr, 'mrr');
                    const relGrade = getMetricGrade(retData.contextRelevanceScore, 'normal');

                    const sourcePillsHtml = (retData.retrievedSources && retData.retrievedSources.length > 0)
                        ? retData.retrievedSources.map(s => `<span class="source-tag">📄 ${escapeHtml(s)}</span>`).join('')
                        : '<span style="color: var(--text-dim); font-size: 0.75rem;">No sources retrieved above threshold</span>';

                    customEvalResult.innerHTML = `
                        <div style="background: rgba(17, 22, 34, 0.6); border: 1px solid var(--border-color); border-radius: var(--radius-md); padding: 16px;">
                            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
                                <div style="font-weight: 700; color: var(--accent-cyan); font-size: 0.9rem; display: flex; align-items: center; gap: 6px;">
                                    <span>🎯</span> Retrieval Evaluation Results
                                </div>
                                <span class="badge ${retData.hitRate >= 0.8 ? 'success' : 'amber'}" style="font-size: 0.72rem; padding: 3px 8px;">
                                    ${retData.hitRate >= 0.8 ? 'PASSED RETRIEVAL' : 'LOW RECALL'}
                                </span>
                            </div>

                            <div class="metrics-grid">
                                <!-- Hit Rate Card -->
                                <div class="metric-row-card" data-metric="hitRate">
                                    <div class="metric-header-row">
                                        <div class="metric-label-group">
                                            <span class="metric-icon">🎯</span>
                                            <span>Hit Rate</span>
                                            <span class="metric-info-trigger" title="Hover to view formula">ℹ️</span>
                                        </div>
                                        <div class="metric-val-pill">
                                            <span class="metric-val-num" style="color: ${hitGrade.color};">${(retData.hitRate * 100).toFixed(0)}%</span>
                                            <span class="metric-grade-pill ${hitGrade.className}">${hitGrade.label}</span>
                                        </div>
                                    </div>
                                    <div class="metric-progress-track">
                                        <div class="metric-progress-fill ${hitGrade.fillClass}" style="width: ${(retData.hitRate * 100).toFixed(0)}%;"></div>
                                    </div>
                                    <div class="metric-desc">Expected keywords found within top-K retrieved context chunks</div>
                                </div>

                                <!-- Precision@K Card -->
                                <div class="metric-row-card" data-metric="precisionAtK">
                                    <div class="metric-header-row">
                                        <div class="metric-label-group">
                                            <span class="metric-icon">📥</span>
                                            <span>Precision@K</span>
                                            <span class="metric-info-trigger" title="Hover to view formula">ℹ️</span>
                                        </div>
                                        <div class="metric-val-pill">
                                            <span class="metric-val-num" style="color: ${precGrade.color};">${(retData.precisionAtK * 100).toFixed(0)}%</span>
                                            <span class="metric-grade-pill ${precGrade.className}">${precGrade.label}</span>
                                        </div>
                                    </div>
                                    <div class="metric-progress-track">
                                        <div class="metric-progress-fill ${precGrade.fillClass}" style="width: ${(retData.precisionAtK * 100).toFixed(0)}%;"></div>
                                    </div>
                                    <div class="metric-desc">Proportion of top-K chunks that contain required reference terms</div>
                                </div>

                                <!-- MRR Card -->
                                <div class="metric-row-card" data-metric="mrr">
                                    <div class="metric-header-row">
                                        <div class="metric-label-group">
                                            <span class="metric-icon">🥇</span>
                                            <span>Mean Reciprocal Rank (MRR)</span>
                                            <span class="metric-info-trigger" title="Hover to view formula">ℹ️</span>
                                        </div>
                                        <div class="metric-val-pill">
                                            <span class="metric-val-num" style="color: ${mrrGrade.color};">${retData.mrr.toFixed(2)}</span>
                                            <span class="metric-grade-pill ${mrrGrade.className}">${mrrGrade.label}</span>
                                        </div>
                                    </div>
                                    <div class="metric-progress-track">
                                        <div class="metric-progress-fill ${mrrGrade.fillClass}" style="width: ${Math.min(100, retData.mrr * 100).toFixed(0)}%;"></div>
                                    </div>
                                    <div class="metric-desc">Positional reciprocal of first relevant chunk (1.00 = 1st rank)</div>
                                </div>

                                <!-- Context Relevance Card -->
                                <div class="metric-row-card" data-metric="contextRel">
                                    <div class="metric-header-row">
                                        <div class="metric-label-group">
                                            <span class="metric-icon">📑</span>
                                            <span>Context Relevance</span>
                                            <span class="metric-info-trigger" title="Hover to view formula">ℹ️</span>
                                        </div>
                                        <div class="metric-val-pill">
                                            <span class="metric-val-num" style="color: ${relGrade.color};">${(retData.contextRelevanceScore * 100).toFixed(1)}%</span>
                                            <span class="metric-grade-pill ${relGrade.className}">${relGrade.label}</span>
                                        </div>
                                    </div>
                                    <div class="metric-progress-track">
                                        <div class="metric-progress-fill ${relGrade.fillClass}" style="width: ${(retData.contextRelevanceScore * 100).toFixed(0)}%;"></div>
                                    </div>
                                    <div class="metric-desc">Query term density and topical overlap in retrieved chunks</div>
                                </div>
                            </div>

                            <!-- Retrieved Sources -->
                            <div style="margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--border-color);">
                                <div style="font-size: 0.72rem; color: var(--text-dim); margin-bottom: 8px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em;">
                                    Retrieved Document Sources (${retData.retrievedSources ? retData.retrievedSources.length : 0})
                                </div>
                                <div style="display: flex; flex-wrap: wrap; gap: 6px;">
                                    ${sourcePillsHtml}
                                </div>
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
