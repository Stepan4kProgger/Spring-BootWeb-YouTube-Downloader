class YouTubeToYtDlpExtension {
    constructor() {
        this.serverUrl = 'http://localhost:8080';
        this.processedElements = new WeakSet();
        this.observer = null;
        this.notificationQueue = [];
        this.isShowingNotification = false;
        this.pendingDownloads = new Map(); // Для отслеживания отправленных запросов
        this.init();
    }

    async init() {
        await this.loadSettings();
        this.injectStyles();
        
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.injectButtons());
        } else {
            this.injectButtons();
        }
    }

    async loadSettings() {
        return new Promise((resolve) => {
            chrome.storage.sync.get(['serverUrl'], (result) => {
                this.serverUrl = result.serverUrl || this.serverUrl;
                resolve();
            });
        });
    }

    injectStyles() {
        if (document.getElementById('yt-dlp-styles')) return;
        
        const style = document.createElement('style');
        style.id = 'yt-dlp-styles';
        style.textContent = `
            .yt-dlp-download-btn {
                position: absolute !important;
                top: 8px !important;
                right: 8px !important;
                z-index: 1000 !important;
                background: linear-gradient(135deg, #ff4444, #cc0000) !important;
                color: white !important;
                border: none !important;
                border-radius: 4px !important;
                padding: 6px 10px !important;
                cursor: pointer !important;
                font-size: 14px !important;
                font-weight: bold !important;
                opacity: 0.9 !important;
                transition: all 0.2s ease !important;
                box-shadow: 0 2px 8px rgba(0,0,0,0.3) !important;
                pointer-events: auto !important;
                font-family: Arial, sans-serif !important;
            }
            
            .yt-dlp-download-btn:hover {
                opacity: 1 !important;
                transform: scale(1.05) !important;
                box-shadow: 0 4px 12px rgba(0,0,0,0.4) !important;
            }
            
            .yt-dlp-notification {
                position: fixed !important;
                top: 20px !important;
                right: 20px !important;
                color: white !important;
                padding: 12px 18px !important;
                border-radius: 6px !important;
                z-index: 100000 !important;
                font-size: 14px !important;
                font-weight: bold !important;
                box-shadow: 0 4px 12px rgba(0,0,0,0.3) !important;
                max-width: 300px !important;
                word-wrap: break-word !important;
                pointer-events: none !important;
                border: 2px solid !important;
            }
            
            .yt-dlp-notification-queue {
                position: fixed !important;
                top: 20px !important;
                right: 20px !important;
                z-index: 100000 !important;
                display: flex !important;
                flex-direction: column !important;
                gap: 10px !important;
                pointer-events: none !important;
            }
            
            .yt-dlp-progress-notification {
                background: #2196F3 !important;
                border-color: #1976D2 !important;
            }
            
            ytd-rich-item-renderer, 
            ytd-video-renderer,
            ytd-grid-video-renderer,
            ytd-compact-video-renderer {
                position: relative !important;
            }
            
            .yt-dlp-download-btn ~ .yt-dlp-download-btn {
                display: none !important;
            }
        `;
        document.head.appendChild(style);
    }

    injectButtons() {
        if (this.observer) {
            this.observer.disconnect();
        }

        this.observer = new MutationObserver((mutations) => {
            this.processThumbnails();
        });

        this.observer.observe(document.body, {
            childList: true,
            subtree: true
        });

        this.processThumbnails();
    }

    processThumbnails() {
        const selectors = [
            'ytd-rich-item-renderer',
            'ytd-video-renderer', 
            'ytd-grid-video-renderer',
            'ytd-compact-video-renderer'
        ].join(',');

        const containers = document.querySelectorAll(selectors);
        
        requestAnimationFrame(() => {
            for (const container of containers) {
                if (this.processedElements.has(container)) continue;
                
                this.processedElements.add(container);
                this.addButtonToThumbnail(container);
            }
        });
    }

    addButtonToThumbnail(container) {
        const oldButtons = container.querySelectorAll('.yt-dlp-download-btn');
        oldButtons.forEach(btn => btn.remove());

        const thumbnailContainer = container.querySelector(
            'a[href*="/watch?v="], ytd-thumbnail, [data-video-id]'
        ) || container;

        if (!this.isValidContainer(thumbnailContainer)) {
            return;
        }

        if (thumbnailContainer.querySelector('.yt-dlp-download-btn')) {
            return;
        }

        const button = this.createDownloadButton();
        thumbnailContainer.style.position = 'relative';
        thumbnailContainer.appendChild(button);

        button.onclick = (e) => {
            e.stopPropagation();
            e.preventDefault();
            this.handleDownloadClick(container);
        };
    }

    isValidContainer(container) {
        return container && container.nodeType === Node.ELEMENT_NODE;
    }

    createDownloadButton() {
        const button = document.createElement('button');
        button.innerHTML = '⬇️';
        button.title = 'Скачать через YT-DLP (с передачей куки авторизации)';
        button.className = 'yt-dlp-download-btn';
        return button;
    }

    async handleDownloadClick(element) {
        try {
            const videoUrl = this.extractVideoUrl(element);
            
            if (!videoUrl) {
                this.showNotification('Не удалось извлечь URL видео', true);
                return;
            }

            console.log('Извлеченный URL:', videoUrl);
            
            // Получаем ID текущей вкладки
            const tabId = await this.getCurrentTabId();
            
            // Создаем уникальный ID для этого запроса
            const requestId = Date.now().toString();
            this.pendingDownloads.set(requestId, { videoUrl, startTime: Date.now() });
            
            // Показываем уведомление о начале загрузки с прогрессом
            const progressNotification = this.showProgressNotification('Отправка видео на скачивание... Сервер обрабатывает запрос...');
            
            chrome.runtime.sendMessage({
                action: 'DOWNLOAD_VIDEO',
                videoUrl: videoUrl,
                tabId: tabId
            }, (response) => {
                // Удаляем из ожидающих независимо от результата
                this.pendingDownloads.delete(requestId);
                
                if (chrome.runtime.lastError) {
                    console.error('Runtime error:', chrome.runtime.lastError);
                    this.showNotification('Ошибка расширения: ' + chrome.runtime.lastError.message, true);
                } else if (response?.success) {
                    const duration = Math.round((Date.now() - this.pendingDownloads.get(requestId)?.startTime) / 1000);
                    this.showNotification(`✅ Видео успешно отправлено на скачивание! (${duration}с)`, false);
                } else {
                    const errorMsg = response?.error || 'Неизвестная ошибка';
                    console.error('Download error:', errorMsg);
                    
                    // Улучшенные сообщения об ошибках сети
                    if (errorMsg.includes('Сервер не ответил в течение 10 минут') || 
                        errorMsg.includes('Таймаут соединения') ||
                        errorMsg.includes('AbortError')) {
                        
                        this.showNotification(
                            '✅ Запрос на скачивание принят сервером!\n\n' +
                            'Сервер обрабатывает видео (это может занять несколько минут).\n' +
                            'Проверьте папку загрузок - файл появится там после завершения.', 
                            false
                        );
                    } else if (errorMsg.includes('Не удалось подключиться к серверу') || 
                               errorMsg.includes('NetworkError') ||
                               errorMsg.includes('Failed to fetch')) {
                        
                        this.showNotification(
                            'Ошибка подключения к серверу YT-DLP:\n\n' +
                            '1. Убедитесь, что сервер запущен\n' +
                            '2. Проверьте настройки URL в расширении\n' +
                            '3. Проверьте блокировку брандмауэром', 
                            true
                        );
                    } else if (errorMsg.includes('authorized') || errorMsg.includes('auth') || errorMsg.includes('login')) {
                        this.showNotification('Ошибка авторизации. Убедитесь, что вы вошли в аккаунт YouTube и перезагрузите страницу.', true);
                    } else {
                        this.showNotification('Ошибка загрузки: ' + errorMsg, true);
                    }
                }
                
                // Закрываем прогресс-уведомление
                if (progressNotification) {
                    setTimeout(() => {
                        progressNotification.remove();
                    }, 2000);
                }
            });
            
        } catch (error) {
            console.error('Ошибка:', error);
            this.showNotification('Ошибка: ' + error.message, true);
        }
    }

    // Новый метод для показа уведомления с прогрессом
    showProgressNotification(message) {
        let queueContainer = document.getElementById('yt-dlp-notification-queue');
        if (!queueContainer) {
            queueContainer = document.createElement('div');
            queueContainer.id = 'yt-dlp-notification-queue';
            queueContainer.className = 'yt-dlp-notification-queue';
            document.body.appendChild(queueContainer);
        }

        const notification = document.createElement('div');
        notification.textContent = message;
        notification.className = 'yt-dlp-notification yt-dlp-progress-notification';
        
        // Добавляем индикатор загрузки
        const spinner = document.createElement('div');
        spinner.style.cssText = `
            display: inline-block;
            width: 16px;
            height: 16px;
            border: 2px solid #ffffff;
            border-radius: 50%;
            border-top-color: transparent;
            animation: yt-dlp-spin 1s linear infinite;
            margin-right: 8px;
            vertical-align: middle;
        `;
        
        notification.insertBefore(spinner, notification.firstChild);
        
        // Добавляем CSS анимацию
        if (!document.getElementById('yt-dlp-spin-animation')) {
            const spinStyle = document.createElement('style');
            spinStyle.id = 'yt-dlp-spin-animation';
            spinStyle.textContent = `
                @keyframes yt-dlp-spin {
                    0% { transform: rotate(0deg); }
                    100% { transform: rotate(360deg); }
                }
            `;
            document.head.appendChild(spinStyle);
        }

        queueContainer.appendChild(notification);

        notification.animate([
            { opacity: 0, transform: 'translateX(100px) translateY(-20px)' },
            { opacity: 1, transform: 'translateX(0) translateY(0)' }
        ], { duration: 300, easing: 'ease-out' });

        return notification;
    }


    getCurrentTabId() {
        return new Promise((resolve, reject) => {
            chrome.runtime.sendMessage({
                action: 'GET_CURRENT_TAB_ID'
            }, (response) => {
                if (chrome.runtime.lastError) {
                    reject(new Error(chrome.runtime.lastError.message));
                    return;
                }
                
                if (response && response.success) {
                    resolve(response.tabId);
                } else {
                    reject(new Error('Failed to get current tab ID: ' + (response?.error || 'Unknown error')));
                }
            });
        });
    }

    extractVideoUrl(element) {
        const link = element.querySelector('a[href*="/watch?v="]') || 
                    element.closest('a[href*="/watch?v="]');
        
        if (link?.href) {
            return this.normalizeYouTubeUrl(link.href);
        }

        const videoId = this.extractVideoIdFromAttributes(element);
        if (videoId) {
            return `https://www.youtube.com/watch?v=${videoId}`;
        }

        return null;
    }

    extractVideoIdFromAttributes(element) {
        const videoElement = element.querySelector('[data-video-id]') || 
                           element.closest('[data-video-id]') || 
                           element;
        
        const videoId = videoElement.getAttribute('data-video-id');
        if (this.isValidVideoId(videoId)) {
            return videoId;
        }

        if (this.isValidVideoId(element.id)) {
            return element.id;
        }

        return null;
    }

    isValidVideoId(id) {
        return id && /^[a-zA-Z0-9_-]{11}$/.test(id);
    }

    normalizeYouTubeUrl(url) {
        try {
            if (url.startsWith('/watch?v=')) {
                return `https://www.youtube.com${url}`;
            }
            
            const urlObj = new URL(url, 'https://www.youtube.com');
            const videoId = urlObj.searchParams.get('v');
            return videoId ? `https://www.youtube.com/watch?v=${videoId}` : url;
        } catch {
            return url;
        }
    }

    showNotification(message, isError = false) {
        this.notificationQueue.push({ message, isError });
        
        if (!this.isShowingNotification) {
            this.processNotificationQueue();
        }
    }

    processNotificationQueue() {
        if (this.notificationQueue.length === 0) {
            this.isShowingNotification = false;
            return;
        }

        this.isShowingNotification = true;
        
        const { message, isError } = this.notificationQueue.shift();
        
        this.createNotificationElement(message, isError);
        
        setTimeout(() => {
            this.processNotificationQueue();
        }, 3000);
    }

    createNotificationElement(message, isError) {
        let queueContainer = document.getElementById('yt-dlp-notification-queue');
        if (!queueContainer) {
            queueContainer = document.createElement('div');
            queueContainer.id = 'yt-dlp-notification-queue';
            queueContainer.className = 'yt-dlp-notification-queue';
            document.body.appendChild(queueContainer);
        }

        const notification = document.createElement('div');
        notification.textContent = message;
        notification.className = 'yt-dlp-notification';
        
        notification.style.background = isError ? '#ff4444' : '#4CAF50';
        notification.style.borderColor = isError ? '#cc0000' : '#45a049';

        queueContainer.appendChild(notification);

        notification.animate([
            { opacity: 0, transform: 'translateX(100px) translateY(-20px)' },
            { opacity: 1, transform: 'translateX(0) translateY(0)' }
        ], { duration: 300, easing: 'ease-out' });

        setTimeout(() => {
            notification.animate([
                { opacity: 1, transform: 'translateX(0) translateY(0)' },
                { opacity: 0, transform: 'translateX(100px) translateY(-20px)' }
            ], { duration: 300, easing: 'ease-in' }).onfinish = () => {
                notification.remove();
                
                if (queueContainer.children.length === 0) {
                    queueContainer.remove();
                }
            };
        }, 2500);
    }

    destroy() {
        if (this.observer) {
            this.observer.disconnect();
            this.observer = null;
        }
        
        this.notificationQueue = [];
        this.isShowingNotification = false;
        
        document.querySelectorAll('.yt-dlp-download-btn, .yt-dlp-notification, #yt-dlp-notification-queue').forEach(el => el.remove());
    }
}

if (!window.ytDlpExtension) {
    window.ytDlpExtension = new YouTubeToYtDlpExtension();
}