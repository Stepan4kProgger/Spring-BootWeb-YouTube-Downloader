# RUS | YouTube Downloader Suite

Набор инструментов для удобной загрузки видео с YouTube с использованием технологии **yt-dlp**. Проект состоит из четырех взаимосвязанных компонентов, обеспечивающих полный цикл работы с видео.

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-green?style=for-the-badge&logo=springboot)
![Chromium](https://img.shields.io/badge/Chromium-Extension-blue?style=for-the-badge&logo=googlechrome)

## 🚀 Особенности

- **📥 Умная загрузка** - Поддержка всех форматов YouTube через yt-dlp
- **🔐 Авторизация** - Передача cookies из браузера для доступа к приватному контенту
- **🌐 Веб-интерфейс** - Удобное управление через браузер
- **🔄 Автообновление** - Автоматическое обновление yt-dlp
- **🎯 Интеграция** - Расширение для прямого скачивания с YouTube
- **📊 Мониторинг** - Отслеживание прогресса загрузки в реальном времени

## 🏗️ Архитектура проекта

Проект состоит из четырех основных компонентов:

### 1. 🎯 **Лаунчер (Launcher)**
- **Назначение**: Координация запуска всех компонентов
- **Технологии**: Spring Boot, Java 21
- **Функции**:
  - Последовательный запуск компонентов
  - Проверка зависимостей
  - Обработка ошибок запуска

### 2. 🔄 **Обновщик (Updater)**
- **Назначение**: Автоматическое обновление yt-dlp
- **Функции**:
  - Проверка новых версий через Google Drive
  - Скачивание и замена yt-dlp.exe
  - Резервное копирование предыдущих версий

### 3. 🌐 **Основное приложение (YT-DLP Web App)**
- **Назначение**: Веб-интерфейс для управления загрузками
- **Технологии**: Spring Boot, Thymeleaf, REST API
- **Функции**:
  - Веб-интерфейс для отправки URL
  - Управление очередью загрузок
  - Просмотр истории и прогресса
  - Настройка параметров загрузки

### 4. 🧩 **Расширение браузера (Browser Extension)**
- **Назначение**: Интеграция с YouTube для быстрого доступа
- **Поддержка**: Chrome, Opera, Edge (все Chromium-based браузеры)
- **Функции**:
  - Кнопки скачивания прямо на YouTube
  - Передача cookies авторизации
  - Уведомления о статусе загрузки

## 📋 Требования

- **Java 21** ([Скачать с Oracle](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html))
- **Браузер на базе Chromium** (Chrome, Opera, Edge и т.д.)
- **Доступ к YouTube**
- **~100 МБ** свободного места на диске

## ⭐ Если проект вам понравился, поставьте звезду на GitHub!

Для вопросов и поддержки создавайте Issues в репозитории проекта.

# ENG | YouTube Downloader Suite

A toolkit for convenient YouTube video downloading using **yt-dlp** technology. The project consists of four interconnected components that provide a complete video processing workflow.

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-blue?style=for-the-badge&logo=springboot)
![Chromium](https://img.shields.io/badge/Chromium-Extension-green?style=for-the-badge&logo=googlechrome)

## 🚀 Features

- **📥 Smart Downloading** - Support for all YouTube formats via yt-dlp
- **🔐 Authorization** - Transfer cookies from browser for accessing private content
- **🌐 Web Interface** - Convenient browser-based management
- **🔄 Auto-Update** - Automatic yt-dlp updates
- **🎯 Integration** - Extension for direct downloading from YouTube
- **📊 Monitoring** - Real-time download progress tracking

## 🏗️ Project Architecture

The project consists of four main components:

### 1. 🎯 Launcher
- **Purpose**: Coordinates the launch of all components
- **Technologies**: Spring Boot, Java 21
- **Functions**:
  - Sequential component startup
  - Dependency checking
  - Launch error handling

### 2. 🔄 Updater
- **Purpose**: Automatic yt-dlp updates
- **Functions**:
  - Checking for new versions via Google Drive
  - Downloading and replacing yt-dlp.exe
  - Backup of previous versions

### 3. 🌐 Main Application (YT-DLP Web App)
- **Purpose**: Web interface for download management
- **Technologies**: Spring Boot, Thymeleaf, REST API
- **Functions**:
  - Web interface for URL submission
  - Download queue management
  - History and progress viewing
  - Download parameter configuration

### 4. 🧩 Browser Extension
- **Purpose**: YouTube integration for quick access
- **Support**: Chrome, Opera, Edge (all Chromium-based browsers)
- **Functions**:
  - Download buttons directly on YouTube
  - Authorization cookie transfer
  - Download status notifications

## 📋 Requirements

- **Java 21** ([Download from Oracle](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html))
- **Chromium-based Browser** (Chrome, Opera, Edge, etc.)
- **Access to YouTube**
- **~100 MB** free disk space

## 🚀 Quick Start

1. Download and install Java 21
2. Launch the application
3. Install the browser extension
4. Start downloading videos directly from YouTube

## 🔧 Configuration

The application automatically handles:
- yt-dlp updates
- Browser cookie synchronization
- Download queue management
- Progress tracking

## 🤝 Support

For issues and feature requests, please check the project documentation or create an issue in the project repository.
