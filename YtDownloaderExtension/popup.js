document.addEventListener('DOMContentLoaded', function() {
    const serverUrlInput = document.getElementById('serverUrl');
    const sendCookiesCheckbox = document.getElementById('sendCookies');
    const saveBtn = document.getElementById('saveBtn');
    const testBtn = document.getElementById('testBtn');
    const statusDiv = document.getElementById('status');

    // Загружаем сохраненные настройки
    chrome.storage.sync.get(['serverUrl', 'sendCookies'], function(result) {
        serverUrlInput.value = result.serverUrl || 'http://localhost:8080';
        // По умолчанию передача cookie включена
        sendCookiesCheckbox.checked = result.sendCookies !== false;
    });

    // Сохранение настроек
    saveBtn.addEventListener('click', function() {
        const serverUrl = serverUrlInput.value.trim();
        const sendCookies = sendCookiesCheckbox.checked;
        
        if (!serverUrl) {
            showStatus('Введите URL сервера', 'error');
            return;
        }

        // Проверяем формат URL
        if (!serverUrl.startsWith('http://') && !serverUrl.startsWith('https://')) {
            showStatus('URL должен начинаться с http:// или https://', 'error');
            return;
        }

        chrome.storage.sync.set({ 
            serverUrl: serverUrl,
            sendCookies: sendCookies
        }, function() {
            let message = 'Настройки сохранены!';
            if (!sendCookies) {
                message += '\n\n⚠️ Передача cookie отключена. Вы не сможете скачивать приватные и возрастные видео.';
            }
            showStatus(message, sendCookies ? 'success' : 'warning');
            
            // Обновляем настройки во вкладках YouTube
            updateYouTubeTabs();
        });
    });

    // Проверка соединения
    testBtn.addEventListener('click', function() {
        const serverUrl = serverUrlInput.value.trim();
        
        if (!serverUrl) {
            showStatus('Введите URL сервера', 'error');
            return;
        }

        showStatus('Проверка соединения...', 'info');

        // Сохраняем URL перед проверкой
        chrome.storage.sync.set({ serverUrl }, function() {
            // Проверяем здоровье сервера
            chrome.runtime.sendMessage({ action: 'CHECK_SERVER' }, (response) => {
                if (chrome.runtime.lastError) {
                    showStatus('Ошибка расширения: ' + chrome.runtime.lastError.message, 'error');
                } else if (response.success) {
                    showStatus('✅ Сервер доступен и работает!\n\nПримечание: скачивание видео может занимать несколько минут.', 'success');
                } else {
                    showStatus('❌ Сервер недоступен: ' + (response.error || 'Неизвестная ошибка'), 'error');
                }
            });
        });
    });

    // Функция для обновления вкладок YouTube
    function updateYouTubeTabs() {
        chrome.tabs.query({url: '*://*.youtube.com/*'}, function(tabs) {
            if (tabs.length > 0) {
                // Сообщаем контент-скриптам об обновлении настроек
                tabs.forEach(tab => {
                    chrome.tabs.sendMessage(tab.id, { 
                        action: 'RELOAD_SETTINGS' 
                    }, function(response) {
                        if (chrome.runtime.lastError) {
                            // Контент-скрипт может быть не загружен
                        }
                    });
                });
            }
        });
    }

    function showStatus(message, type) {
        statusDiv.textContent = message;
        statusDiv.className = `status ${type}`;
        statusDiv.style.display = 'block';
        statusDiv.style.whiteSpace = 'pre-line';
        
        // Не скрываем сообщения об ошибках автоматически
        if (type !== 'error' && type !== 'warning') {
            setTimeout(() => {
                statusDiv.style.display = 'none';
            }, 5000);
        }
    }
});