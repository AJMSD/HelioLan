(function bootstrapUtils(global) {
    "use strict";

    var PREFS_KEY = "heliolan_dashboard_prefs_v1";

    var DEFAULT_PREFS = {
        timeFormat: "12h",
        sleepTrendWindow: 14,
        cardioTrendWindow: 14,
        activityTrendWindow: 14,
        refreshBehavior: "periodic",
        syncWindow: "30_days"
    };

    function cloneDefaults() {
        return JSON.parse(JSON.stringify(DEFAULT_PREFS));
    }

    function loadPreferences() {
        try {
            var raw = global.localStorage.getItem(PREFS_KEY);
            if (!raw) {
                return cloneDefaults();
            }
            var parsed = JSON.parse(raw);
            return Object.assign(cloneDefaults(), parsed || {});
        } catch (_unused) {
            return cloneDefaults();
        }
    }

    function savePreferences(next) {
        var prefs = Object.assign(cloneDefaults(), next || {});
        try {
            global.localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
        } catch (_unused) {
            // Keep in-memory preferences when localStorage is unavailable.
        }
        return prefs;
    }

    function formatNumber(value) {
        if (value === null || value === undefined || Number.isNaN(Number(value))) {
            return "--";
        }
        return new Intl.NumberFormat(undefined).format(Number(value));
    }

    function formatDurationMs(valueMs) {
        if (!valueMs || Number(valueMs) <= 0) {
            return "--";
        }
        var totalMinutes = Math.floor(Number(valueMs) / 60000);
        var hours = Math.floor(totalMinutes / 60);
        var minutes = totalMinutes % 60;
        if (hours <= 0) {
            return minutes + "m";
        }
        if (minutes <= 0) {
            return hours + "h";
        }
        return hours + "h " + minutes + "m";
    }

    function formatDate(value) {
        if (!value) {
            return "--";
        }
        var date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "--";
        }
        return new Intl.DateTimeFormat(undefined, {
            month: "short",
            day: "numeric",
            year: "numeric"
        }).format(date);
    }

    function formatTime(value, prefs) {
        if (!value) {
            return "--";
        }
        var date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "--";
        }
        var use24Hour = (prefs && prefs.timeFormat) === "24h";
        return new Intl.DateTimeFormat(undefined, {
            hour: "numeric",
            minute: "2-digit",
            hour12: !use24Hour
        }).format(date);
    }

    function formatDateTime(value, prefs) {
        if (!value) {
            return "--";
        }
        var date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "--";
        }
        var use24Hour = (prefs && prefs.timeFormat) === "24h";
        return new Intl.DateTimeFormat(undefined, {
            month: "short",
            day: "numeric",
            hour: "numeric",
            minute: "2-digit",
            hour12: !use24Hour
        }).format(date);
    }

    function formatRelativeTime(value) {
        if (!value) {
            return "unknown";
        }
        var date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return String(value);
        }
        var deltaSeconds = Math.round((Date.now() - date.getTime()) / 1000);
        var absolute = Math.abs(deltaSeconds);
        if (absolute < 60) {
            return "just now";
        }
        if (absolute < 3600) {
            var minutes = Math.floor(absolute / 60);
            return minutes + "m " + (deltaSeconds >= 0 ? "ago" : "from now");
        }
        if (absolute < 86400) {
            var hours = Math.floor(absolute / 3600);
            return hours + "h " + (deltaSeconds >= 0 ? "ago" : "from now");
        }
        var days = Math.floor(absolute / 86400);
        return days + "d " + (deltaSeconds >= 0 ? "ago" : "from now");
    }

    function toDateInputValue(value) {
        var date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "";
        }
        var month = String(date.getMonth() + 1).padStart(2, "0");
        var day = String(date.getDate()).padStart(2, "0");
        return date.getFullYear() + "-" + month + "-" + day;
    }

    function startOfDayIso(dateInput) {
        var date = dateInput instanceof Date ? new Date(dateInput.getTime()) : new Date(dateInput);
        if (Number.isNaN(date.getTime())) {
            return null;
        }
        date.setHours(0, 0, 0, 0);
        return date.toISOString();
    }

    function endOfDayIso(dateInput) {
        var date = dateInput instanceof Date ? new Date(dateInput.getTime()) : new Date(dateInput);
        if (Number.isNaN(date.getTime())) {
            return null;
        }
        date.setHours(23, 59, 59, 999);
        return date.toISOString();
    }

    function shiftDate(dateInput, days) {
        var date = dateInput instanceof Date ? new Date(dateInput.getTime()) : new Date(dateInput);
        date.setDate(date.getDate() + days);
        return date;
    }

    function clamp(value, min, max) {
        return Math.min(max, Math.max(min, value));
    }

    function safeArray(value) {
        return Array.isArray(value) ? value : [];
    }

    function average(values) {
        var numbers = safeArray(values).map(Number).filter(function keepFinite(item) {
            return Number.isFinite(item);
        });
        if (!numbers.length) {
            return 0;
        }
        return numbers.reduce(function sum(total, item) {
            return total + item;
        }, 0) / numbers.length;
    }

    function varianceMinutes(isoValues) {
        var minutes = safeArray(isoValues).map(function toMinutes(value) {
            var date = new Date(value);
            if (Number.isNaN(date.getTime())) {
                return null;
            }
            return date.getHours() * 60 + date.getMinutes();
        }).filter(function keep(item) {
            return item !== null;
        });

        if (minutes.length <= 1) {
            return 0;
        }
        var mean = average(minutes);
        var totalVariance = minutes.reduce(function variance(sum, item) {
            var diff = item - mean;
            return sum + (diff * diff);
        }, 0) / minutes.length;
        return Math.sqrt(totalVariance);
    }

    function trendDirection(current, previous) {
        if (!Number.isFinite(current) || !Number.isFinite(previous) || previous === 0) {
            return "flat";
        }
        var deltaRatio = (current - previous) / Math.abs(previous);
        if (Math.abs(deltaRatio) < 0.05) {
            return "flat";
        }
        return deltaRatio > 0 ? "up" : "down";
    }

    function trendLabel(direction) {
        if (direction === "up") {
            return "Up";
        }
        if (direction === "down") {
            return "Down";
        }
        return "Stable";
    }

    function toMetricMap(freshnessPayload) {
        var payload = freshnessPayload || {};
        return {
            steps: payload.steps || "unknown",
            sleep: payload.sleep || "unknown",
            heartRate: payload.heart_rate || "unknown",
            restingHeartRate: payload.resting_hr || "unknown"
        };
    }

    global.HelioUtils = {
        DEFAULT_PREFS: DEFAULT_PREFS,
        loadPreferences: loadPreferences,
        savePreferences: savePreferences,
        formatNumber: formatNumber,
        formatDurationMs: formatDurationMs,
        formatDate: formatDate,
        formatTime: formatTime,
        formatDateTime: formatDateTime,
        formatRelativeTime: formatRelativeTime,
        toDateInputValue: toDateInputValue,
        startOfDayIso: startOfDayIso,
        endOfDayIso: endOfDayIso,
        shiftDate: shiftDate,
        clamp: clamp,
        safeArray: safeArray,
        average: average,
        varianceMinutes: varianceMinutes,
        trendDirection: trendDirection,
        trendLabel: trendLabel,
        toMetricMap: toMetricMap
    };
})(window);
