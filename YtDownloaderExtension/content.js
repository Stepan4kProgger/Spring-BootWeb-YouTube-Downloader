class YouTubeToYtDlpExtension {
    constructor() {
        this.serverUrl = 'http://localhost:8080';
        this.sendCookies = true;
        this.processedThumbnails = new WeakSet();
        this.observer = null;
        this.notificationQueue = [];
        this.isShowingNotification = false;
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
                this.sendCookies = result.sendCookies !== false;
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

            .yt-dlp-progress-notification {
                background: #2196F3 !important;
                border-color: #1976D2 !important;
            }

            /* Относительное позиционирование для контейнеров миниатюр */
            a.yt-lockup-view-model__content-image,
            div#thumbnail.style-scope.ytd-rich-grid-media,
            a.ytLockupViewModelContentImage,
            ytd-thumbnail {
                position: relative !important;
            }
        `;
        document.head.appendChild(style);
    }

    injectButtons() {
        if (this.observer) {
            this.observer.disconnect();
        }

        // Расширенный наблюдатель для старых и новых типов миниатюр
        this.observer = new MutationObserver((mutations) => {
            let shouldProcess = false;

            for (const mutation of mutations) {
                for (const node of mutation.addedNodes) {
                    if (node.nodeType === Node.ELEMENT_NODE) {
                        // Проверяем добавление релевантных элементов
                        if (node.matches && (
                            // Старые селекторы
                            node.matches('a.yt-lockup-view-model__content-image[href*="/watch?v="]') ||
                            node.matches('div#thumbnail.style-scope.ytd-rich-grid-media') ||
                            // Новые селекторы
                            node.matches('a.ytLockupViewModelContentImage[href*="/watch?v="]') ||
                            node.matches('ytd-rich-item-renderer') ||
                            // Прочие
                            node.matches('ytd-grid-video-renderer') ||
                            node.matches('ytd-playlist-video-renderer') ||
                            node.matches('ytd-video-renderer') ||
                            // Поиск в дочерних элементах
                            node.querySelector && (
                                node.querySelector('a.yt-lockup-view-model__content-image[href*="/watch?v="]') ||
                                node.querySelector('div#thumbnail.style-scope.ytd-rich-grid-media') ||
                                node.querySelector('a.ytLockupViewModelContentImage[href*="/watch?v="]') ||
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
        // Комбинированный селектор для всех типов миниатюр
        const videoContainers = document.querySelectorAll(`
            a.yt-lockup-view-model__content-image[href*="/watch?v="],
            div#thumbnail.style-scope.ytd-rich-grid-media,
            a.ytLockupViewModelContentImage[href*="/watch?v="],
            ytd-rich-item-renderer a.ytLockupViewModelContentImage[href*="/watch?v="],
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

        // Обработка разных типов контейнеров
        if (container.matches('a.yt-lockup-view-model__content-image')) {
            // Старый класс (может ещё встречаться)
            container.style.position = 'relative';
            container.appendChild(button);
        }
        else if (container.matches('div#thumbnail.style-scope.ytd-rich-grid-media')) {
            // Старый контейнер rich-grid-media
            container.style.position = 'relative';
            container.appendChild(button);
        }
        else if (container.matches('a.ytLockupViewModelContentImage')) {
            // Новый класс
            container.style.position = 'relative';
            container.appendChild(button);
        }
        else if (container.matches('ytd-rich-item-renderer a.ytLockupViewModelContentImage')) {
            // Ссылка внутри нового рендерера
            container.style.position = 'relative';
            container.appendChild(button);
        }
        else if (container.matches('ytd-grid-video-renderer a#thumbnail.yt-simple-endpoint')) {
            const thumbnailParent = container.closest('ytd-thumbnail');
            if (thumbnailParent) {
                thumbnailParent.style.position = 'relative';
                thumbnailParent.appendChild(button);
            } else {
                container.style.position = 'relative';
                container.appendChild(button);
            }
        }
        else if (container.matches('ytd-playlist-video-renderer a#thumbnail.yt-simple-endpoint')) {
            const thumbnailParent = container.closest('ytd-thumbnail');
            if (thumbnailParent) {
                thumbnailParent.style.position = 'relative';
                thumbnailParent.appendChild(button);
            } else {
                container.style.position = 'relative';
                container.appendChild(button);
            }
        }
        else if (container.matches('ytd-video-renderer a#thumbnail.yt-simple-endpoint')) {
            const thumbnailParent = container.closest('ytd-thumbnail');
            if (thumbnailParent) {
                thumbnailParent.style.position = 'relative';
                thumbnailParent.appendChild(button);
            } else {
                container.style.position = 'relative';
                container.appendChild(button);
            }
        }

        button.onclick = (e) => {
            e.stopPropagation();
            e.preventDefault();

            // Определяем элемент, содержащий URL видео
            let videoElement = container;

            // Для старых контейнеров rich-grid-media ищем ссылку внутри
            if (container.matches('div#thumbnail.style-scope.ytd-rich-grid-media')) {
                const videoLink = container.querySelector('a[href*="/watch?v="]');
                if (videoLink) {
                    videoElement = videoLink;
                }
            }
            // Для нового ytd-rich-item-renderer (если контейнер - это сам рендерер или вложенная ссылка)
            else if (container.closest('ytd-rich-item-renderer')) {
                const link = container.closest('ytd-rich-item-renderer').querySelector('a.ytLockupViewModelContentImage[href*="/watch?v="]');
                if (link) videoElement = link;
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

            const startTime = Date.now();

            let progressMessage = 'Отправка видео на скачивание...';
            progressMessage += this.sendCookies
                ? ' (с передачей cookie)'
                : ' (без передачи cookie)';
            const progressNotification = this.showProgressNotification(progressMessage);

            chrome.runtime.sendMessage({
                action: 'DOWNLOAD_VIDEO',
                videoUrl: videoUrl
            }, (response) => {
                if (progressNotification && progressNotification.parentNode) {
                    progressNotification.remove();
                }

                const duration = Math.round((Date.now() - startTime) / 1000);

                if (chrome.runtime.lastError) {
                    console.error('Runtime error:', chrome.runtime.lastError);
                    this.showNotification('Ошибка расширения: ' + chrome.runtime.lastError.message, true);
                    return;
                }

                if (response && response.success) {
                    const dl = response.result || {};
                    const file = dl.filename || videoUrl;
                    this.showNotification(`✅ Скачано за ${duration}с: ${file}`, false);
                    return;
                }

                const errorMsg = (response && response.error) || 'Неизвестная ошибка';

                if (!this.sendCookies && (
                    errorMsg.includes('Sign in') ||
                    errorMsg.includes('auth') ||
                    errorMsg.includes('login') ||
                    errorMsg.includes('private') ||
                    errorMsg.includes('age-restricted') ||
                    errorMsg.includes('bot')
                )) {
                    this.showNotification(
                        '❌ Для скачивания этого видео нужны cookie.\n\n' +
                        'Включите "Передавать cookie-файлы" в настройках\n' +
                        'и убедитесь, что вы авторизованы на YouTube.',
                        true
                    );
                } else if (
                    errorMsg.includes('Сервер не ответил') ||
                    errorMsg.includes('Таймаут')
                ) {
                    this.showNotification(
                        '⏱ Сервер не ответил за 10 минут.\n\n' +
                        'Проверьте папку загрузок — возможно, файл уже скачан.',
                        true
                    );
                } else if (
                    errorMsg.includes('Не удалось подключиться') ||
                    errorMsg.includes('NetworkError') ||
                    errorMsg.includes('Failed to fetch')
                ) {
                    this.showNotification(
                        'Ошибка подключения к серверу YT-DLP:\n\n' +
                        '1. Сервер запущен?\n' +
                        '2. URL в настройках верный?\n' +
                        '3. Брандмауэр не блокирует?',
                        true
                    );
                } else {
                    this.showNotification('Ошибка загрузки: ' + errorMsg, true);
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
        notification.className = 'yt-dlp-notification yt-dlp-progress-notification';

        const spinner = document.createElement('span');
        spinner.style.cssText = `
            display: inline-block;
            width: 14px;
            height: 14px;
            border: 2px solid #ffffff;
            border-radius: 50%;
            border-top-color: transparent;
            animation: yt-dlp-spin 1s linear infinite;
            margin-right: 8px;
            vertical-align: middle;
        `;
        notification.appendChild(spinner);
        notification.appendChild(document.createTextNode(message));

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

        queueContainer.insertBefore(notification, queueContainer.firstChild);

        notification.animate([
            { opacity: 0, transform: 'translateY(-100%)' },
            { opacity: 1, transform: 'translateY(0)' }
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
        // Старый класс
        if (element.matches('a.yt-lockup-view-model__content-image')) {
            return this.normalizeYouTubeUrl(element.href);
        }
        // Старый контейнер rich-grid-media
        if (element.matches('div#thumbnail.style-scope.ytd-rich-grid-media')) {
            const link = element.querySelector('a[href*="/watch?v="]');
            if (link) return this.normalizeYouTubeUrl(link.href);
        }
        // Новый класс
        if (element.matches('a.ytLockupViewModelContentImage')) {
            return this.normalizeYouTubeUrl(element.href);
        }
        // Новый рендерер
        if (element.closest('ytd-rich-item-renderer')) {
            const link = element.closest('ytd-rich-item-renderer').querySelector('a.ytLockupViewModelContentImage[href*="/watch?v="]');
            if (link) return this.normalizeYouTubeUrl(link.href);
        }
        // Прочие (старые страницы каналов, плейлистов и т.д.)
        if (element.href) {
            return this.normalizeYouTubeUrl(element.href);
        }
        return null;
    }

    normalizeYouTubeUrl(url) {
        try {
            if (url.startsWith('/watch?v=')) {
                return `https://www.youtube.com${url}`;
            }

            const urlObj = new URL(url, 'https://www.youtube.com');

            const shortsMatch = urlObj.pathname.match(/^\/shorts\/([A-Za-z0-9_-]+)/);
            if (shortsMatch) {
                return `https://www.youtube.com/watch?v=${shortsMatch[1]}`;
            }

            if (urlObj.hostname === 'youtu.be') {
                const id = urlObj.pathname.slice(1).split('/')[0];
                if (id) return `https://www.youtube.com/watch?v=${id}`;
            }

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

        queueContainer.insertBefore(notification, queueContainer.firstChild);

        notification.animate([
            { opacity: 0, transform: 'translateY(-100%) translateX(0)' },
            { opacity: 1, transform: 'translateY(0) translateX(0)' }
        ], { duration: 300, easing: 'ease-out' });

        setTimeout(() => {
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
