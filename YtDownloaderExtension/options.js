document.addEventListener('DOMContentLoaded', function() {
    // Загрузка сохраненных настроек
    chrome.storage.sync.get(['serverUrl', 'defaultFormat'], function(data) {
        document.getElementById('serverUrl').value = 
            data.serverUrl || 'http://localhost:8080';
        document.getElementById('defaultFormat').value = 
            data.defaultFormat || '';
    });

    // Сохранение настроек
    document.getElementById('save').addEventListener('click', function() {
        const settings = {
            serverUrl: document.getElementById('serverUrl').value,
            defaultFormat: document.getElementById('defaultFormat').value
        };
        
        chrome.storage.sync.set(settings, function() {
            alert('Settings saved successfully!');
        });
    });

    // Сброс настроек
    document.getElementById('reset').addEventListener('click', function() {
        if (confirm('Reset all settings to defaults?')) {
            chrome.storage.sync.clear(function() {
                alert('Settings reset to defaults');
                location.reload();
            });
        }
    });
});