chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'DOWNLOAD_VIDEO') {
        handleDownload(request.videoUrl, sender.tab.id)
            .then(result => {
                if (result && result.success === false) {
                    sendResponse({
                        success: false,
                        error: result.error || result.message || 'Download failed'
                    });
                } else {
                    sendResponse({ success: true, result });
                }
            })
            .catch(error => {
                console.error('Download error in message listener:', error);
                sendResponse({ success: false, error: error.message });
            });
        return true;
    }

    if (request.action === 'CHECK_SERVER') {
        checkServerHealth()
            .then(isHealthy => sendResponse({ success: isHealthy }))
            .catch(error => sendResponse({ success: false, error: error.message }));
        return true;
    }

    if (request.action === 'GET_COOKIES') {
        getYouTubeCookies(sender.tab.id)
            .then(cookies => sendResponse({ success: true, cookies }))
            .catch(error => sendResponse({ success: false, error: error.message }));
        return true;
    }

    if (request.action === 'GET_SETTINGS') {
        getSettings()
            .then(settings => sendResponse({ success: true, settings }))
            .catch(error => sendResponse({ success: false, error: error.message }));
        return true;
    }
});

async function getSettings() {
    return new Promise(resolve => {
        chrome.storage.sync.get(['serverUrl', 'sendCookies'], (result) => {
            resolve({
                serverUrl: result.serverUrl || 'http://localhost:8080',
                sendCookies: result.sendCookies !== false
            });
        });
    });
}

async function getYouTubeCookies(tabId) {
    return new Promise((resolve, reject) => {
        chrome.tabs.get(tabId, (tab) => {
            if (chrome.runtime.lastError) {
                reject(new Error(chrome.runtime.lastError.message));
                return;
            }
            if (!tab.url || !tab.url.includes('youtube.com')) {
                reject(new Error('Not a YouTube tab'));
                return;
            }

            const urls = [
                'https://www.youtube.com/',
                'https://m.youtube.com/',
                'https://studio.youtube.com/'
            ];

            const fetches = urls.map(u => new Promise(res => {
                chrome.cookies.getAll({ url: u }, (cookies) => {
                    if (chrome.runtime.lastError) {
                        console.warn(`Error getting cookies for ${u}:`, chrome.runtime.lastError);
                        res([]);
                    } else {
                        res(cookies || []);
                    }
                });
            }));

            Promise.all(fetches)
                .then(results => {
                    const allCookies = results.flat();
                    const seen = new Set();
                    const uniqueCookies = [];
                    for (const c of allCookies) {
                        const key = `${c.name}|${c.domain}|${c.path}`;
                        if (!seen.has(key)) {
                            seen.add(key);
                            uniqueCookies.push(c);
                        }
                    }

                    const hasAuth = uniqueCookies.some(c =>
                        c.name === 'SAPISID' ||
                        c.name === '__Secure-3PSID' ||
                        c.name === 'SSID'
                    );
                    if (!hasAuth) {
                        console.warn('No authentication cookies (SAPISID / __Secure-3PSID / SSID) found. Private and age-restricted videos will not be downloadable.');
                    }

                    const cookieString = uniqueCookies
                        .filter(c => c.value)
                        .map(c => `${c.name}=${c.value}`)
                        .join('; ');

                    resolve({
                        cookieString,
                        rawCookies: uniqueCookies,
                        hasAuth
                    });
                })
                .catch(reject);
        });
    });
}

async function handleDownload(videoUrl, tabId) {
    let settings;
    try {
        settings = await getSettings();
        const serverUrl = settings.serverUrl;
        const sendCookies = settings.sendCookies;

        let youTubeCookies = null;
        if (sendCookies) {
            try {
                youTubeCookies = await getYouTubeCookies(tabId);
            } catch (cookieError) {
                console.warn('Could not get YouTube cookies:', cookieError.message);
            }
        }

        const downloadRequest = {
            url: videoUrl,
            cookies: (youTubeCookies && youTubeCookies.cookieString) || null
        };

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 10 * 60 * 1000);

        try {
            const response = await fetch(`${serverUrl}/api/download`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json'
                },
                body: JSON.stringify(downloadRequest),
                signal: controller.signal
            });

            clearTimeout(timeoutId);

            if (!response.ok) {
                const errorText = await response.text();
                console.error('Server error response:', errorText);

                if (response.status === 404) {
                    throw new Error('Сервер не найден. Проверьте URL сервера в настройках расширения.');
                } else if (response.status >= 500) {
                    throw new Error('Ошибка сервера: ' + errorText);
                }
                throw new Error(`HTTP ${response.status}: ${errorText}`);
            }

            return await response.json();
        } catch (fetchError) {
            clearTimeout(timeoutId);
            throw fetchError;
        }
    } catch (error) {
        console.error('Download error details:', {
            name: error.name,
            message: error.message
        });

        const serverUrl = settings?.serverUrl || 'http://localhost:8080';

        if (error.name === 'AbortError') {
            throw new Error('Сервер не ответил в течение 10 минут. Проверьте папку загрузок — возможно, файл уже скачан.');
        }
        if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
            throw new Error(
                'Не удалось подключиться к серверу YT-DLP. Убедитесь, что:\n' +
                '1. Сервер запущен на ' + serverUrl + '\n' +
                '2. Нет блокировки брандмауэром\n' +
                '3. Сервер доступен из браузера'
            );
        }
        throw error;
    }
}

async function checkServerHealth() {
    try {
        const settings = await getSettings();
        const serverUrl = settings.serverUrl || 'http://localhost:8080';

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);

        const response = await fetch(`${serverUrl}/api/download/health`, {
            method: 'GET',
            headers: { 'Accept': 'application/json' },
            signal: controller.signal
        });

        clearTimeout(timeoutId);
        return response.ok;
    } catch (error) {
        console.error('Server health check failed:', error);
        return false;
    }
}

chrome.runtime.onInstalled.addListener((details) => {
    if (details.reason === 'install') {
        chrome.runtime.openOptionsPage();
    }
});
