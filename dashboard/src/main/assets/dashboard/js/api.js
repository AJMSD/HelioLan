(function bootstrapApi(global) {
    "use strict";

    var CACHE_NAMESPACE = "heliolan_cache_v1_";
    var DEFAULT_TTL_MS = 5 * 60 * 1000;

    function looksLikeHtml(text, contentType) {
        if (!text) return false;
        if ((contentType || "").toLowerCase().indexOf("text/html") >= 0) return true;
        return /^\s*<!doctype html/i.test(text) || /^\s*<html/i.test(text) || /^\s*</.test(text);
    }

    function ApiError(message, status, code, details, retryAfterSeconds) {
        this.name = "ApiError";
        this.message = message;
        this.status = status || 0;
        this.code = code || "UNKNOWN_ERROR";
        this.details = details || null;
        this.retryAfterSeconds = retryAfterSeconds || null;
    }
    ApiError.prototype = Object.create(Error.prototype);
    ApiError.prototype.constructor = ApiError;

    function ApiClient(options) {
        options = options || {};
        this.baseUrl = (options.baseUrl || global.location.origin || "").replace(/\/+$/, "");
        this.defaultTtlMs = options.defaultTtlMs || DEFAULT_TTL_MS;
    }

    ApiClient.prototype._buildUrl = function _buildUrl(path, query) {
        var normalizedPath = path.charAt(0) === "/" ? path : "/" + path;
        var url = this.baseUrl + "/api/v1" + normalizedPath;
        var params = new URLSearchParams();
        Object.keys(query || {}).forEach(function eachKey(key) {
            var value = query[key];
            if (value !== null && value !== undefined && value !== "") {
                params.append(key, String(value));
            }
        });
        var queryText = params.toString();
        return queryText ? url + "?" + queryText : url;
    };

    ApiClient.prototype._cacheKey = function _cacheKey(key) {
        return CACHE_NAMESPACE + key;
    };

    ApiClient.prototype._readCache = function _readCache(key) {
        try {
            var raw = global.sessionStorage.getItem(this._cacheKey(key));
            if (!raw) {
                return null;
            }
            var parsed = JSON.parse(raw);
            if (!parsed.expiresAt || !parsed.payload) {
                global.sessionStorage.removeItem(this._cacheKey(key));
                return null;
            }
            if (Date.now() > parsed.expiresAt) {
                global.sessionStorage.removeItem(this._cacheKey(key));
                return null;
            }
            return parsed.payload;
        } catch (_unused) {
            return null;
        }
    };

    ApiClient.prototype._writeCache = function _writeCache(key, payload, ttlMs) {
        try {
            global.sessionStorage.setItem(
                this._cacheKey(key),
                JSON.stringify({
                    expiresAt: Date.now() + (ttlMs || this.defaultTtlMs),
                    payload: payload
                })
            );
        } catch (_unused) {
            // Browser quota or disabled storage should not break dashboard behavior.
        }
    };

    ApiClient.prototype.clearCache = function clearCache(prefix) {
        var fullPrefix = prefix ? this._cacheKey(prefix) : CACHE_NAMESPACE;
        var removals = [];
        for (var i = 0; i < global.sessionStorage.length; i += 1) {
            var key = global.sessionStorage.key(i);
            if (key && key.indexOf(fullPrefix) === 0) {
                removals.push(key);
            }
        }
        removals.forEach(function removeEntry(entry) {
            global.sessionStorage.removeItem(entry);
        });
    };

    ApiClient.prototype.request = async function request(method, path, options) {
        options = options || {};
        var query = options.query || null;
        var body = options.body || null;
        var headers = options.headers || {};
        var cacheKey = options.cacheKey || null;
        var ttlMs = options.ttlMs || this.defaultTtlMs;
        var forceRefresh = Boolean(options.forceRefresh);
        var allowErrorStatus = options.allowErrorStatus || null;

        if (method === "GET" && cacheKey && !forceRefresh) {
            var cached = this._readCache(cacheKey);
            if (cached) {
                return cached;
            }
        }

        var url = this._buildUrl(path, query);
        var requestHeaders = Object.assign({ Accept: "application/json" }, headers);
        if (body && !requestHeaders["Content-Type"]) {
            requestHeaders["Content-Type"] = "application/json";
        }

        var response;
        try {
            response = await fetch(url, {
                method: method,
                headers: requestHeaders,
                body: body ? JSON.stringify(body) : undefined,
                credentials: "same-origin",
                cache: "no-store"
            });
        } catch (_networkError) {
            throw new ApiError(
                "Unable to reach the HelioLAN server.",
                0,
                "NETWORK_ERROR",
                null,
                null
            );
        }

        var text = await response.text();
        var contentType = (response.headers.get("Content-Type") || "").toLowerCase();
        var payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch (_parseError) {
                var snippet = text.slice(0, 200);
                if (looksLikeHtml(text, contentType)) {
                    throw new ApiError(
                        "API returned HTML instead of JSON. Open the dashboard from /dashboard/ and verify API calls use /api/v1/*.",
                        response.status,
                        "UNEXPECTED_HTML_RESPONSE",
                        { url: url, snippet: snippet },
                        null
                    );
                }
                if (!response.ok) {
                    throw new ApiError(
                        "Server returned malformed JSON.",
                        response.status,
                        "INVALID_JSON",
                        snippet,
                        null
                    );
                }
                throw new ApiError(
                    "Unexpected response format.",
                    response.status,
                    "INVALID_RESPONSE",
                    snippet,
                    null
                );
            }
        }

        if (!response.ok) {
            if (allowErrorStatus && allowErrorStatus === response.status) {
                return payload;
            }
            var code = payload && payload.error && payload.error.code ? payload.error.code : "HTTP_" + response.status;
            var message = payload && payload.error && payload.error.message ? payload.error.message : response.statusText;
            var retryAfterHeader = response.headers.get("Retry-After");
            var retryAfter = retryAfterHeader ? Number(retryAfterHeader) : null;
            throw new ApiError(message || "Request failed.", response.status, code, payload, retryAfter);
        }

        if (method === "GET" && cacheKey) {
            this._writeCache(cacheKey, payload, ttlMs);
        }
        return payload;
    };

    ApiClient.prototype.getSession = function getSession(forceRefresh) {
        return this.request("GET", "/auth/session", {
            cacheKey: "auth_session",
            ttlMs: 15 * 1000,
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.login = async function login(passcode) {
        var result = await this.request("POST", "/auth/login", { body: { passcode: passcode } });
        this.clearCache("auth_");
        return result;
    };

    ApiClient.prototype.logout = async function logout() {
        var result = await this.request("POST", "/auth/logout", {});
        this.clearCache();
        return result;
    };

    ApiClient.prototype.setPasscode = async function setPasscode(passcode, currentPasscode) {
        var body = { passcode: passcode };
        if (currentPasscode) {
            body.currentPasscode = currentPasscode;
        }
        var result = await this.request("POST", "/auth/passcode", { body: body });
        this.clearCache("auth_");
        return result;
    };

    ApiClient.prototype.setOpenAccess = async function setOpenAccess(enabled, confirm) {
        var result = await this.request("POST", "/auth/open-access", {
            body: { enabled: Boolean(enabled), confirm: Boolean(confirm) }
        });
        this.clearCache("auth_");
        return result;
    };

    ApiClient.prototype.getToday = function getToday(forceRefresh) {
        return this.request("GET", "/today", {
            cacheKey: "today_summary",
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getHeartRate = function getHeartRate(params, forceRefresh) {
        var query = params || {};
        return this.request("GET", "/heartrate", {
            query: query,
            cacheKey: "heartrate_" + JSON.stringify(query),
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getSleep = function getSleep(params, forceRefresh) {
        var query = params || {};
        return this.request("GET", "/sleep", {
            query: query,
            cacheKey: "sleep_" + JSON.stringify(query),
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getSteps = function getSteps(params, forceRefresh) {
        var query = params || {};
        return this.request("GET", "/steps", {
            query: query,
            cacheKey: "steps_" + JSON.stringify(query),
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getRestingHeartRate = function getRestingHeartRate(params, forceRefresh) {
        var query = params || {};
        return this.request("GET", "/resting-hr", {
            query: query,
            cacheKey: "resting_hr_" + JSON.stringify(query),
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getHrv = function getHrv(forceRefresh) {
        return this.request("GET", "/hrv", {
            cacheKey: "hrv",
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getAggregates = function getAggregates(params, forceRefresh) {
        var query = params || {};
        return this.request("GET", "/aggregates", {
            query: query,
            cacheKey: "aggregates_" + JSON.stringify(query),
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getSyncStatus = function getSyncStatus(forceRefresh) {
        return this.request("GET", "/sync/status", {
            cacheKey: "sync_status",
            ttlMs: 10 * 1000,
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.triggerSync = async function triggerSync() {
        var result = await this.request("POST", "/sync/trigger", {});
        this.clearCache("today");
        this.clearCache("sync_");
        return result;
    };

    ApiClient.prototype.getPermissions = function getPermissions(forceRefresh) {
        return this.request("GET", "/permissions", {
            cacheKey: "permissions",
            ttlMs: 20 * 1000,
            forceRefresh: Boolean(forceRefresh)
        });
    };

    ApiClient.prototype.getServerInfo = function getServerInfo(forceRefresh, tolerateAuthError) {
        return this.request("GET", "/server/info", {
            cacheKey: "server_info",
            ttlMs: 20 * 1000,
            forceRefresh: Boolean(forceRefresh),
            allowErrorStatus: tolerateAuthError ? 401 : null
        });
    };

    ApiClient.prototype.probeServer = async function probeServer() {
        await this.getServerInfo(true, true);
        return true;
    };

    ApiClient.prototype.buildExportCsvUrl = function buildExportCsvUrl(type, from, to) {
        return this._buildUrl("/export/csv", { type: type, from: from, to: to });
    };

    ApiClient.prototype.buildExportAllUrl = function buildExportAllUrl(from, to) {
        return this._buildUrl("/export/all", { from: from, to: to });
    };

    global.HelioApi = {
        ApiClient: ApiClient,
        ApiError: ApiError
    };
    global.heliolanApi = new ApiClient();
})(window);
