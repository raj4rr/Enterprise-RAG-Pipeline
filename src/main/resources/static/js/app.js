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

    // --- Slider Bindings ---
    const chunkSizeSlider = document.getElementById('chunkSize');
    const chunkSizeVal = document.getElementById('chunkSizeVal');
    if (chunkSizeSlider && chunkSizeVal) {
        chunkSizeSlider.addEventListener('input', (e) => {
            chunkSizeVal.textContent = e.target.value;
            // Ensure overlap cannot exceed chunk size
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

    // --- Preview Chunks ---
    const btnPreview = document.getElementById('btnPreview');
    const previewContainer = document.getElementById('previewContainer');
    const previewCountBadge = document.getElementById('previewCountBadge');

    if (btnPreview) {
        btnPreview.addEventListener('click', async () => {
            const textInput = document.getElementById('rawTextInput').value.trim();
            const chunkSize = parseInt(document.getElementById('chunkSize').value);
            const chunkOverlap = parseInt(document.getElementById('chunkOverlap').value);

            let previewText = textInput;
            if (!previewText && selectedFile) {
                // If it's a plain text or markdown file, read client-side for instant preview
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
                btnPreview.textContent = 'Generating Preview...';

                const res = await fetch('/api/rag/preview-chunks', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text: previewText,
                        chunkSize: chunkSize,
                        chunkOverlap: chunkOverlap
                    })
                });

                const data = await res.json();
                renderChunks(data.chunks, data.totalChunks, data.totalCharacters);
            } catch (err) {
                alert("Error generating preview: " + err.message);
            } finally {
                btnPreview.disabled = false;
                btnPreview.textContent = 'Preview Chunks';
            }
        });
    }

    function renderChunks(chunks, totalChunks, totalChars) {
        if (!previewContainer) return;
        previewContainer.innerHTML = '';

        if (previewCountBadge) {
            previewCountBadge.textContent = `${totalChunks} chunks (${totalChars} chars)`;
            previewCountBadge.style.display = 'inline-block';
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
            const rawText = document.getElementById('rawTextInput').value.trim();

            if (!selectedFile && !rawText) {
                alert("Please upload a file or enter text to ingest.");
                return;
            }

            try {
                btnIngest.disabled = true;
                btnIngest.innerHTML = 'Embedding & Ingesting into Vector DB...';
                if (ingestStatusBanner) ingestStatusBanner.style.display = 'none';

                let res, data;
                if (selectedFile) {
                    const formData = new FormData();
                    formData.append('file', selectedFile);
                    formData.append('chunkSize', chunkSize);
                    formData.append('chunkOverlap', chunkOverlap);

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
                            chunkOverlap: chunkOverlap
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
                        renderChunks(data.sampleChunks, data.totalChunks, data.totalCharacters);
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
                btnIngest.innerHTML = '⚡ Ingest & Save to Vector DB';
            }
        });
    }

    // --- Vector DB Status & Reset ---
    const btnResetVector = document.getElementById('btnResetVector');
    if (btnResetVector) {
        btnResetVector.addEventListener('click', async () => {
            if (!confirm("Are you sure you want to clear the Vector DB? All stored embeddings will be removed.")) return;
            try {
                const res = await fetch('/api/rag/reset', { method: 'DELETE' });
                const data = await res.json();
                alert(data.message);
                loadStatusAndDocuments();
            } catch (e) {
                alert("Error resetting vector store: " + e.message);
            }
        });
    }

    // --- Vector Search Test ---
    const btnSearchTest = document.getElementById('btnSearchTest');
    const searchResultsContainer = document.getElementById('searchResultsContainer');
    if (btnSearchTest) {
        btnSearchTest.addEventListener('click', async () => {
            const query = document.getElementById('searchQuery').value.trim();
            const topK = document.getElementById('searchTopK').value;
            const threshold = document.getElementById('searchThreshold').value;

            if (!query) {
                alert("Please enter a search query.");
                return;
            }

            try {
                btnSearchTest.disabled = true;
                btnSearchTest.textContent = 'Searching...';

                const url = `/api/rag/search?query=${encodeURIComponent(query)}&topK=${topK}&threshold=${threshold}`;
                const res = await fetch(url, { method: 'POST' });
                const sources = await res.json();

                if (searchResultsContainer) {
                    searchResultsContainer.innerHTML = '';
                    if (sources.length === 0) {
                        searchResultsContainer.innerHTML = '<div style="color: var(--text-muted); font-size: 0.85rem;">No chunks matched the similarity threshold.</div>';
                        return;
                    }
                    sources.forEach(s => {
                        const div = document.createElement('div');
                        div.className = 'chunk-card';
                        div.innerHTML = `
                            <div class="chunk-header">
                                <span class="chunk-badge">Rank #${s.rank} | ${s.sourceName}</span>
                                <span class="source-score">Similarity: ${(s.similarityScore * 100).toFixed(1)}%</span>
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
                btnSearchTest.textContent = 'Run Vector Search';
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

    // Prompt pills
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

        // Add assistant typing placeholder
        const placeholderId = 'msg-' + Date.now();
        appendMessage('assistant', '<span class="typing">Thinking & Searching Vector DB...</span>', null, placeholderId);

        try {
            const res = await fetch('/api/rag/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    query: query,
                    topK: 4,
                    similarityThreshold: 0.50
                })
            });

            const data = await res.json();
            updateAssistantMessage(placeholderId, data.answer, data.sources, data.elapsedTimeMs, data.modelUsed);
        } catch (err) {
            updateAssistantMessage(placeholderId, "Error connecting to RAG service: " + err.message);
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

    function updateAssistantMessage(elementId, answer, sources, durationMs, model) {
        const msgDiv = document.getElementById(elementId);
        if (!msgDiv) return;

        const contentEl = msgDiv.querySelector('.message-content');
        if (contentEl) {
            contentEl.innerHTML = formatMarkdown(answer);
        }

        if (sources && sources.length > 0) {
            const bubble = msgDiv.querySelector('.bubble');
            const sourcesBox = document.createElement('div');
            sourcesBox.className = 'sources-box';

            let sourcesHtml = `
                <button class="sources-toggle" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'flex' : 'none'">
                    📚 Retrieved Context (${sources.length} sources) • ${durationMs}ms ▾
                </button>
                <div class="sources-list" style="display: none;">
            `;

            sources.forEach(s => {
                sourcesHtml += `
                    <div class="source-item">
                        <div class="source-item-header">
                            <strong>${escapeHtml(s.sourceName)}</strong> (Chunk #${s.chunkIndex + 1})
                            <span class="source-score">${(s.similarityScore * 100).toFixed(0)}% match</span>
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

            const docsRes = await fetch('/api/rag/documents');
            const docsData = await docsRes.json();

            if (docCountBadge) {
                docCountBadge.textContent = `${docsData.totalFiles} files`;
            }

            const storedDocsList = document.getElementById('storedDocsList');
            if (storedDocsList) {
                storedDocsList.innerHTML = '';
                if (docsData.files.length === 0) {
                    storedDocsList.innerHTML = '<li style="color: var(--text-dim);">No documents uploaded yet.</li>';
                } else {
                    docsData.files.forEach(f => {
                        const li = document.createElement('li');
                        li.style.padding = '6px 0';
                        li.style.borderBottom = '1px solid rgba(255,255,255,0.05)';
                        li.textContent = '📄 ' + f;
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
        // Bold
        html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
        // Bullet points
        html = html.replace(/^\s*-\s+(.*)$/gm, '• $1');
        // Newlines
        html = html.replace(/\n/g, '<br/>');
        return html;
    }

    // Initial status fetch
    loadStatusAndDocuments();
});
