// Background script для обработки запросов и обхода CORS
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'DOWNLOAD_VIDEO') {
        handleDownload(request.videoUrl, sender.tab.id)
            .then(result => {
                sendResponse({ success: true, result });
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

    if (request.action === 'GET_CURRENT_TAB_ID') {
        chrome.tabs.query({active: true, currentWindow: true}, (tabs) => {
            if (chrome.runtime.lastError) {
                sendResponse({success: false, error: chrome.runtime.lastError.message});
                return;
            }
            
            if (tabs.length === 0) {
                sendResponse({success: false, error: 'No active tab found'});
                return;
            }
            
            sendResponse({success: true, tabId: tabs[0].id});
        });
        return true;
    }
});

// Улучшенная функция для получения куки с YouTube
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

            // Получаем куки для всех доменов YouTube
            const domains = ['.youtube.com', 'www.youtube.com'];
            let allCookies = [];

            // Функция для получения куки для одного домена
            const getCookiesForDomain = (domain) => {
                return new Promise((resolve, reject) => {
                    chrome.cookies.getAll({ domain }, (cookies) => {
                        if (chrome.runtime.lastError) {
                            console.warn(`Error getting cookies for ${domain}:`, chrome.runtime.lastError);
                            resolve([]);
                        } else {
                            console.log(`Found ${cookies.length} cookies for ${domain}`);
                            resolve(cookies || []);
                        }
                    });
                });
            };

            // Получаем куки для всех доменов
            Promise.all(domains.map(domain => getCookiesForDomain(domain)))
                .then(results => {
                    // Объединяем результаты
                    results.forEach(cookies => allCookies = allCookies.concat(cookies));
                    
                    // Удаляем дубликаты по имени, домену и пути
                    const uniqueCookies = [];
                    const seen = new Set();
                    
                    for (const cookie of allCookies) {
                        const key = `${cookie.name}|${cookie.domain}|${cookie.path}`;
                        if (!seen.has(key)) {
                            seen.add(key);
                            uniqueCookies.push(cookie);
                        }
                    }

                    // Форматируем куки в строку для заголовка
                    const cookieString = uniqueCookies.map(cookie => 
                        `${cookie.name}=${cookie.value}`
                    ).join('; ');

                    console.log(`Total unique cookies: ${uniqueCookies.length}`);
                    console.log(`Cookie string length: ${cookieString.length}`);
                    
                    if (uniqueCookies.length === 0) {
                        console.warn('No cookies found for YouTube domains');
                    }

                    resolve({
                        cookieString: cookieString,
                        rawCookies: uniqueCookies
                    });
                })
                .catch(error => {
                    console.error('Error in cookie collection:', error);
                    reject(error);
                });
        });
    });
}

// Функция для получения настроек
async function getSettings() {
    return new Promise(resolve => {
        chrome.storage.sync.get(['serverUrl'], resolve);
    });
}

async function handleDownload(videoUrl, tabId) {
    let settings;
    
    try {
        settings = await getSettings();
        const serverUrl = settings.serverUrl || 'http://localhost:8080';
        
        console.log('Attempting download with server URL:', serverUrl);
        
        // Получаем куки YouTube
        let youTubeCookies = null;
        try {
            youTubeCookies = await getYouTubeCookies(tabId);
            
            if (!youTubeCookies.cookieString || youTubeCookies.rawCookies.length === 0) {
                console.warn('No YouTube cookies found. Download may fail for age-restricted or private content.');
            } else {
                console.log(`Successfully retrieved ${youTubeCookies.rawCookies.length} cookies`);
            }
        } catch (cookieError) {
            console.warn('Could not get YouTube cookies:', cookieError.message);
        }

        // Создаем объект запроса
        const downloadRequest = {
            url: videoUrl,
            downloadDirectory: "",
            format: "",
            cookies: youTubeCookies ? youTubeCookies.cookieString : null
        };

        console.log('Sending download request to:', `${serverUrl}/api/download`);
        console.log('Video URL:', videoUrl);
        console.log('Cookies provided:', !!downloadRequest.cookies);

        // УВЕЛИЧИВАЕМ ТАЙМАУТ ДО 10 МИНУТ для долгих операций
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 10 * 60 * 1000); // 10 минут

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

            console.log('Response status:', response.status);
            
            if (!response.ok) {
                let errorText = await response.text();
                console.error('Server error response:', errorText);
                
                // Более информативные сообщения об ошибках
                if (response.status === 0) {
                    throw new Error('Не удалось подключиться к серверу. Убедитесь, что сервер YT-DLP запущен на ' + serverUrl);
                } else if (response.status === 404) {
                    throw new Error('Сервер не найден. Проверьте URL сервера в настройках расширения.');
                } else if (response.status >= 500) {
                    throw new Error('Ошибка сервера: ' + errorText);
                } else {
                    throw new Error(`HTTP ${response.status}: ${errorText}`);
                }
            }

            const result = await response.json();
            console.log('Download successful, result:', result);
            
            return result;
        } catch (fetchError) {
            clearTimeout(timeoutId);
            throw fetchError;
        }
        
    } catch (error) {
        console.error('Download error details:', {
            name: error.name,
            message: error.message,
            stack: error.stack
        });
        
        // Улучшенные сообщения об ошибках
        const serverUrl = settings?.serverUrl || 'http://localhost:8080';
        
        if (error.name === 'AbortError') {
            throw new Error('Сервер не ответил в течение 10 минут. Это нормально для больших видео - проверьте папку загрузок, файл может быть уже скачан.');
        } else if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
            throw new Error('Не удалось подключиться к серверу YT-DLP. Убедитесь, что:\n\n1. Сервер запущен на ' + serverUrl + '\n2. Нет блокировки брандмауэром\n3. Сервер доступен из браузера');
        } else {
            throw error;
        }
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
            headers: {
                'Accept': 'application/json'
            },
            signal: controller.signal
        });

        clearTimeout(timeoutId);
        return response.ok;
    } catch (error) {
        console.error('Server health check failed:', error);
        return false;
    }
}

// Периодическая проверка сервера
setInterval(() => {
    checkServerHealth().then(isHealthy => {
        if (!isHealthy) {
            console.warn('YT-DLP server is not responding');
        }
    });
}, 30000);

// Уведомление при установке
chrome.runtime.onInstalled.addListener((details) => {
    if (details.reason === 'install') {
        chrome.tabs.create({
            url: chrome.runtime.getURL('popup.html')
        });
    }
});