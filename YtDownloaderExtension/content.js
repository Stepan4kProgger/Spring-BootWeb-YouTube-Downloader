class YouTubeToYtDlpExtension {
    constructor() {
        this.serverUrl = 'http://localhost:8080';
        this.sendCookies = true; // По умолчанию включено
        this.processedThumbnails = new WeakSet();
        this.observer = null;
        this.notificationQueue = [];
        this.isShowingNotification = false;
        this.pendingDownloads = new Map();
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

        this.addMessageListener();
    }

    async loadSettings() {
        return new Promise((resolve) => {
            chrome.storage.sync.get(['serverUrl', 'sendCookies'], (result) => {
                this.serverUrl = result.serverUrl || this.serverUrl;
                this.sendCookies = result.sendCookies !== false; // true по умолчанию
                resolve();
            });
        });
    }

    addMessageListener() {
        chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
            if (request.action === 'RELOAD_SETTINGS') {
                this.loadSettings().then(() => {
                    sendResponse({ success: true });
                });
                return true;
            }
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
                left: 8px !important;
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
                margin-bottom: 10px !important;
            }
            
            .yt-dlp-notification-queue {
                position: fixed !important;
                top: 20px !important;
                right: 20px !important;
                z-index: 100000 !important;
                display: flex !important;
                flex-direction: column-reverse !important;
                gap: 10px !important;
                pointer-events: none !important;
                max-height: 80vh !important;
                overflow: hidden !important;
            }
            
            /* Прогресс-уведомление теперь выглядит как информационное */
            .yt-dlp-progress-notification {
                background: #2196F3 !important;
                border-color: #1976D2 !important;
            }
            
            /* Стили для различных типов миниатюр */
            a.yt-lockup-view-model__content-image {
                position: relative !important;
            }
            
            div#thumbnail.style-scope.ytd-rich-grid-media {
                position: relative !important;
            }
            
            ytd-grid-video-renderer ytd-thumbnail {
                position: relative !important;
            }
            
            ytd-playlist-video-renderer ytd-thumbnail {
                position: relative !important;
            }
            
            ytd-video-renderer ytd-thumbnail {
                position: relative !important;
            }
        `;
        document.head.appendChild(style);
    }

    injectButtons() {
        if (this.observer) {
            this.observer.disconnect();
        }

        // Расширенный наблюдатель для всех типов миниатюр
        this.observer = new MutationObserver((mutations) => {
            let shouldProcess = false;
            
            for (const mutation of mutations) {
                for (const node of mutation.addedNodes) {
                    if (node.nodeType === Node.ELEMENT_NODE) {
                        // Проверяем, добавлены ли наши целевые элементы
                        if (node.matches && (
                            node.matches('a.ytLockupViewModelContentImage[href*="/watch?v="]') ||
                            node.matches('ytd-rich-item-renderer') ||
                            node.matches('ytd-grid-video-renderer') ||
                            node.matches('ytd-playlist-video-renderer') ||
                            node.matches('ytd-video-renderer') ||
                            node.querySelector && (
                                node.querySelector('a.yt-lockup-view-model__content-image[href*="/watch?v="]') ||
                                node.querySelector('div#thumbnail.style-scope.ytd-rich-grid-media') ||
                                node.querySelector('ytd-grid-video-renderer a#thumbnail.yt-simple-endpoint[href*="/watch?v="]') ||
                                node.querySelector('ytd-playlist-video-renderer a#thumbnail.yt-simple-endpoint[href*="/watch?v="]') ||
                                node.querySelector('ytd-video-renderer a#thumbnail.yt-simple-endpoint[href*="/watch?v="]')
                            )
                        )) {
                            shouldProcess = true;
                            break;
                        }
                    }
                }
                if (shouldProcess) break;
            }
            
            if (shouldProcess) {
                this.processThumbnails();
            }
        });

        this.observer.observe(document.body, {
            childList: true,
            subtree: true
        });

        this.processThumbnails();
    }

    processThumbnails() {
        // Расширяем селекторы для всех типов миниатюр
        const videoContainers = document.querySelectorAll(`
            a.ytLockupViewModelContentImage[href*="/watch?v="],
            ytd-rich-item-renderer:not([hidden]) a.ytLockupViewModelContentImage[href*="/watch?v="],
            ytd-grid-video-renderer a#thumbnail.yt-simple-endpoint[href*="/watch?v="],
            ytd-playlist-video-renderer a#thumbnail.yt-simple-endpoint[href*="/watch?v="],
            ytd-video-renderer a#thumbnail.yt-simple-endpoint[href*="/watch?v="]
        `);
        
        requestAnimationFrame(() => {
            for (const container of videoContainers) {
                if (this.processedThumbnails.has(container)) continue;
                
                this.processedThumbnails.add(container);
                this.addButtonToThumbnail(container);
            }
        });
    }

    addButtonToThumbnail(container) {
        // Проверяем, есть ли уже кнопка
        if (container.querySelector('.yt-dlp-download-btn')) {
            return;
        }

        const button = this.createDownloadButton();
        
        // Для первого типа (новый интерфейс) - добавляем прямо в контейнер
        if (container.matches('a.ytLockupViewModelContentImage')) {
            container.style.position = 'relative';
            container.appendChild(button);
        } 
        // Для второго типа (контейнер rich-grid-media) - добавляем в этот контейнер
        else if (container.matches('div#thumbnail.style-scope.ytd-rich-grid-media')) {
            container.style.position = 'relative';
            container.appendChild(button);
        }
        // Для третьего типа (страница канала) - добавляем в родительский ytd-thumbnail
        else if (container.matches('ytd-grid-video-renderer a#thumbnail.yt-simple-endpoint')) {
            const thumbnailParent = container.closest('ytd-thumbnail');
            if (thumbnailParent) {
                thumbnailParent.style.position = 'relative';
                thumbnailParent.appendChild(button);
            } else {
                // Fallback
                container.style.position = 'relative';
                container.appendChild(button);
            }
        }
        // Для четвертого типа (плейлисты, включая "Понравившиеся") - добавляем в родительский ytd-thumbnail
        else if (container.matches('ytd-playlist-video-renderer a#thumbnail.yt-simple-endpoint')) {
            const thumbnailParent = container.closest('ytd-thumbnail');
            if (thumbnailParent) {
                thumbnailParent.style.position = 'relative';
                thumbnailParent.appendChild(button);
            } else {
                // Fallback
                container.style.position = 'relative';
                container.appendChild(button);
            }
        }
        // Для пятого типа (раздел "История") - добавляем в родительский ytd-thumbnail
        else if (container.matches('ytd-video-renderer a#thumbnail.yt-simple-endpoint')) {
            const thumbnailParent = container.closest('ytd-thumbnail');
            if (thumbnailParent) {
                thumbnailParent.style.position = 'relative';
                thumbnailParent.appendChild(button);
            } else {
                // Fallback
                container.style.position = 'relative';
                container.appendChild(button);
            }
        }

        button.onclick = (e) => {
            e.stopPropagation();
            e.preventDefault();
            
            // Для разных типов контейнеров находим соответствующий элемент с видео
            let videoElement = container;
            if (container.matches('a.ytLockupViewModelContentImage')) {
                // Ссылка уже является самим контейнером
                videoElement = container;
            } else if (container.closest('ytd-rich-item-renderer')) {
                // Для вложенных случаев
                const videoLink = container.closest('ytd-rich-item-renderer').querySelector('a.ytLockupViewModelContentImage[href*="/watch?v="]');
                if (videoLink) videoElement = videoLink;
            }
            
            this.handleDownloadClick(videoElement);
        };
    }

    createDownloadButton() {
        const button = document.createElement('button');
        button.innerHTML = '⬇️';
        button.title = 'Скачать через YT-DLP';
        button.className = 'yt-dlp-download-btn';
        return button;
    }

    async handleDownloadClick(thumbnail) {
        try {
            const videoUrl = this.extractVideoUrl(thumbnail);
            
            if (!videoUrl) {
                this.showNotification('Не удалось извлечь URL видео', true);
                return;
            }

            console.log('Извлеченный URL:', videoUrl);
            console.log('Настройки cookie:', this.sendCookies);
            
            // Получаем ID текущей вкладки
            const tabId = await this.getCurrentTabId();
            
            // Создаем уникальный ID для этого запроса
            const requestId = Date.now().toString();
            this.pendingDownloads.set(requestId, { videoUrl, startTime: Date.now() });
            
            // Обновляем сообщение в зависимости от настроек cookie
            let progressMessage = 'Отправка видео на скачивание...';
            if (!this.sendCookies) {
                progressMessage += ' (без передачи cookie)';
            } else {
                progressMessage += ' (с передачей cookie)';
            }
            
            this.showProgressNotification(progressMessage);
            
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
                    
                    // Если cookie отключены и ошибка связана с доступом
                    if (!this.sendCookies && (
                        errorMsg.includes('authorized') || 
                        errorMsg.includes('auth') || 
                        errorMsg.includes('login') ||
                        errorMsg.includes('private') ||
                        errorMsg.includes('age-restricted')
                    )) {
                        this.showNotification(
                            '❌ Для скачивания этого видео необходима передача cookie.\n\n' +
                            'Включите "Передавать cookie-файлы" в настройках расширения\n' +
                            'и убедитесь, что вы авторизованы на YouTube.',
                            true
                        );
                    } else if (errorMsg.includes('Сервер не ответил в течение 10 минут') || 
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
                    } else {
                        this.showNotification('Ошибка загрузки: ' + errorMsg, true);
                    }
                }
            });
            
        } catch (error) {
            console.error('Ошибка:', error);
            this.showNotification('Ошибка: ' + error.message, true);
        }
    }

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

        // Добавляем в начало контейнера (сверху)
        queueContainer.insertBefore(notification, queueContainer.firstChild);

        // Анимация появления - выезжает сверху
        notification.animate([
            { opacity: 0, transform: 'translateY(-100%) translateX(0)' },
            { opacity: 1, transform: 'translateY(0) translateX(0)' }
        ], { duration: 300, easing: 'ease-out' });

        // Автоматически скрываем через 2.5 секунды с такой же анимацией, как у обычных уведомлений
        setTimeout(() => {
            // Такая же анимация исчезновения, как в createNotificationElement
            notification.animate([
                { opacity: 1, transform: 'translateX(0) translateY(0)' },
                { opacity: 0, transform: 'translateX(100px) translateY(-20px)' }
            ], { duration: 300, easing: 'ease-in' }).onfinish = () => {
                notification.remove();
                
                if (queueContainer.children.length === 0) {
                    queueContainer.remove();
                }
            };
        }, 2500); // 2.5 секунды - такой же интервал, как у обычных уведомлений

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
        // Если это контейнер rich-grid-media, ищем ссылку внутри
        if (element.matches('a.ytLockupViewModelContentImage')) {
            return this.normalizeYouTubeUrl(element.href);
        }
        // Для ytd-rich-item-renderer ищем вложенную ссылку
        if (element.closest('ytd-rich-item-renderer')) {
            const link = element.closest('ytd-rich-item-renderer').querySelector('a.ytLockupViewModelContentImage[href*="/watch?v="]');
            if (link) return this.normalizeYouTubeUrl(link.href);
        }
        // Для остальных случаев работаем как раньше
        else if (element.href) {
            return this.normalizeYouTubeUrl(element.href);
        }
        
        return null;
    }

    extractVideoIdFromAttributes(element) {
        // Ищем data-video-id в самой миниатюре или родительских элементах
        const videoElement = element.querySelector('[data-video-id]') || 
                           element.closest('[data-video-id]');
        
        if (videoElement) {
            const videoId = videoElement.getAttribute('data-video-id');
            if (this.isValidVideoId(videoId)) {
                return videoId;
            }
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

        // Добавляем уведомление в начало контейнера (сверху)
        queueContainer.insertBefore(notification, queueContainer.firstChild);

        // Анимация появления - выезжает сверху
        notification.animate([
            { opacity: 0, transform: 'translateY(-100%) translateX(0)' },
            { opacity: 1, transform: 'translateY(0) translateX(0)' }
        ], { duration: 300, easing: 'ease-out' });

        setTimeout(() => {
            // Анимация исчезновения - уезжает вправо
            notification.animate([
                { opacity: 1, transform: 'translateY(0) translateX(0)' },
                { opacity: 0, transform: 'translateY(0) translateX(100%)' }
            ], { duration: 300, easing: 'ease-in' }).onfinish = () => {
                notification.remove();
                
                if (queueContainer.children.length === 0) {
                    queueContainer.remove();
                }
            };
        }, 3000);
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