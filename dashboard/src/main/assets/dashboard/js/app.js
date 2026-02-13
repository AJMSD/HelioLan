
(function initApp(global, doc) {
    "use strict";

    function reportBootstrapError(message) {
        var subtitle = doc.getElementById("loginSubtitle");
        var authMessage = doc.getElementById("authMessage");
        if (subtitle) {
            subtitle.textContent = "Dashboard initialization failed.";
        }
        if (authMessage) {
            authMessage.className = "message error";
            authMessage.textContent = message;
        }
        if (global.console && typeof global.console.error === "function") {
            global.console.error(message);
        }
    }

    var missingDependencies = [];
    if (!global.HelioApi) missingDependencies.push("api.js");
    if (!global.HelioUtils) missingDependencies.push("utils.js");
    if (!global.HelioCharts) missingDependencies.push("charts.js");
    if (missingDependencies.length) {
        reportBootstrapError(
            "Required dashboard scripts did not load (" +
            missingDependencies.join(", ") +
            "). Hard refresh and verify /dashboard/* assets are not blocked."
        );
        return;
    }

    var api = global.heliolanApi || new global.HelioApi.ApiClient();
    var u = global.HelioUtils;
    var charts = global.HelioCharts;
    var views = ["today", "sleep", "cardio", "activity", "nutrition", "settings"];

    var s = {
        authed: false,
        session: null,
        view: "today",
        prefs: u.loadPreferences(),
        date: {
            sleep: u.toDateInputValue(new Date()),
            cardio: u.toDateInputValue(new Date()),
            nutrition: u.toDateInputValue(new Date())
        },
        timer: { ping: null, todayPoll: null, autoSync: null, viewRefresh: null },
        rendering: false
    };

    var el = {};

    function byId(id) { return doc.getElementById(id); }
    function hasKey(obj, key) {
        return Object.prototype.hasOwnProperty.call(obj, key);
    }
    function data(p) {
        if (!p || typeof p !== "object") return null;
        return hasKey(p, "data") ? p.data : p;
    }
    function meta(p) {
        if (!p || typeof p !== "object") return null;
        return hasKey(p, "meta") ? p.meta : null;
    }
    function setVisible(node, visible) {
        if (!node) return;
        node.classList.toggle("hidden", !visible);
        node.hidden = !visible;
    }
    function sessionPasscodeConfigured(sess) {
        if (!sess || typeof sess !== "object") return false;
        if (typeof sess.passcode_configured === "boolean") return sess.passcode_configured;
        if (typeof sess.passcodeConfigured === "boolean") return sess.passcodeConfigured;
        return false;
    }
    function esc(v) {
        return String(v === null || v === undefined ? "" : v)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
    }

    function formatApiError(err, fallback) {
        if (!err || typeof err !== "object") return fallback;
        if (err.code === "UNEXPECTED_HTML_RESPONSE") {
            return "Dashboard API returned HTML instead of JSON. Reload /dashboard/ and verify asset routing.";
        }
        if (err.code === "INVALID_CREDENTIALS") {
            return "Incorrect passcode. Use the passcode configured in the HelioLAN app.";
        }
        if (err.code === "AUTH_LOCKED") {
            if (err.retryAfterSeconds) {
                return "Too many failed attempts. Try again in " + err.retryAfterSeconds + " seconds.";
            }
            return "Too many failed attempts. Try again later.";
        }
        if (err.code === "PASSCODE_NOT_CONFIGURED") {
            return "No passcode is configured yet. Set one now.";
        }
        return err.message || fallback;
    }

    function setAuth(msg, type) {
        if (!el.authMessage) return;
        el.authMessage.className = "message" + (type ? " " + type : "");
        el.authMessage.textContent = msg || "";
    }

    function setStatus(msg, type) {
        if (!el.globalStatus) return;
        el.globalStatus.className = "global-status" + (type ? " " + type : "");
        el.globalStatus.textContent = msg || "";
    }

    function showLogin(passcodeConfigured) {
        s.authed = false;
        charts.destroyAll();
        stopBackgroundUpdates();
        setVisible(el.loginScreen, true);
        setVisible(el.appShell, false);
        setVisible(el.loginForm, passcodeConfigured);
        setVisible(el.setPasscodeForm, !passcodeConfigured);
        el.loginSubtitle.textContent = passcodeConfigured
            ? "Enter your dashboard passcode."
            : "No passcode configured. Set one now.";
    }

    function showApp() {
        s.authed = true;
        setVisible(el.loginScreen, false);
        setVisible(el.appShell, true);
        setAuth("");
        startBackgroundUpdates();
    }

    function setOffline(isOffline) { el.offlineBanner.hidden = !isOffline; }
    function parseHash() {
        var raw = (global.location.hash || "").replace(/^#/, "").trim();
        return views.indexOf(raw) >= 0 ? raw : "today";
    }

    function markNav() {
        var buttons = el.viewNav.querySelectorAll(".nav-item");
        Array.prototype.forEach.call(buttons, function each(b) {
            b.classList.toggle("active", b.getAttribute("data-view") === s.view);
        });
    }

    function skeleton() {
        return "<section class=\"card-grid\">" +
            "<article class=\"card span-4 skeleton skeleton-card\"></article>" +
            "<article class=\"card span-4 skeleton skeleton-card\"></article>" +
            "<article class=\"card span-4 skeleton skeleton-card\"></article>" +
            "<article class=\"card span-12 skeleton skeleton-chart\"></article>" +
            "</section>";
    }

    function empty(msg) {
        return "<div class=\"empty-state\"><strong>No data</strong><p>" + esc(msg) + "</p></div>";
    }

    function hintLinks() {
        var links = el.viewContainer.querySelectorAll(".hint-link");
        Array.prototype.forEach.call(links, function each(link) {
            link.addEventListener("click", function onClick(evt) {
                evt.preventDefault();
                var reason = link.getAttribute("data-reason") || "";
                if (reason === "hrv") {
                    setStatus("HRV values depend on source-device support and granted permissions.");
                    return;
                }
                if (reason === "resting-hr") {
                    setStatus("Resting HR can be missing if source sync is incomplete.");
                    return;
                }
                setStatus("Data gaps are usually permissions or source-sync related.");
            });
        });
    }

    async function checkSession(force) {
        if (el.loginSubtitle) {
            el.loginSubtitle.textContent = "Checking server status...";
        }
        try {
            var sess = data(await api.getSession(Boolean(force))) || {};
            s.session = sess;
            if (sess.authenticated) {
                showApp();
                setStatus("Signed in. Dashboard is online.", "success");
                s.view = parseHash();
                markNav();
                await render(true);
            } else {
                var configured = sessionPasscodeConfigured(sess);
                showLogin(configured);
                if (configured) {
                    setAuth("Server online. Enter your passcode to continue.", "success");
                } else {
                    setAuth("No passcode is configured yet. Set one to continue.");
                }
            }
        } catch (err) {
            showLogin(true);
            if (el.loginSubtitle) {
                el.loginSubtitle.textContent = "Unable to validate server session.";
            }
            setAuth(formatApiError(err, "Unable to validate session."), "error");
        }
    }

    function updateLastSynced(rows) {
        rows = u.safeArray(rows);
        if (!rows.length) {
            el.lastSyncedPill.textContent = "Last synced: never";
            return;
        }
        var newest = rows
            .map(function map(r) { return Date.parse(r.last_sync_time || ""); })
            .filter(function finite(t) { return Number.isFinite(t); })
            .sort(function desc(a, b) { return b - a; })[0];
        if (!newest) {
            el.lastSyncedPill.textContent = "Last synced: unknown";
            return;
        }
        el.lastSyncedPill.textContent = "Last synced: " + u.formatRelativeTime(new Date(newest).toISOString());
    }

    async function renderToday(force, silent) {
        if (!silent) {
            el.viewContainer.innerHTML = skeleton();
        }
        try {
            var pair = await Promise.all([api.getToday(force), api.getSyncStatus(force)]);
            var today = data(pair[0]) || {};
            var fresh = u.toMetricMap((meta(pair[0]) && meta(pair[0]).freshness) || {});
            updateLastSynced(data(pair[1]));

            var steps = Number(today.steps_today || 0);
            var pct = u.clamp(Math.round((steps / 10000) * 100), 0, 100);
            var hr = today.latest_heart_rate;
            var sl = today.latest_sleep;
            var rhr = today.latest_resting_hr;
            var activeCalories = Number(today.active_calories_today || 0);
            var distanceMeters = Number(today.distance_today_meters || 0);
            var totalCalories = Number(today.total_calories_today_kcal || 0);
            var latestNutrition = today.latest_nutrition;
            var latestSpO2 = today.latest_oxygen_saturation;
            var latestHrv = today.latest_hrv;

            el.viewContainer.innerHTML = "<section class=\"view-head\"><div><p class=\"eyebrow\">Today</p><h3>Live health snapshot</h3></div><div class=\"view-actions\"><button id=\"todaySync\" class=\"btn btn-primary\" type=\"button\">Sync Now</button></div></section>" +
                "<section class=\"card-grid\">" +
                "<article class=\"card span-4\"><h4>Steps</h4><div class=\"progress-ring\" style=\"--progress:" + pct + ";\"><strong>" + pct + "%</strong></div><p class=\"metric\">" + u.formatNumber(steps) + "</p><p class=\"metric-sub\">Goal: 10,000</p></article>" +
                "<article class=\"card span-4\"><h4>Latest HR</h4>" + (hr ? "<p class=\"metric\">" + esc(hr.bpm) + " bpm</p><p class=\"metric-sub\">" + esc(u.formatDateTime(hr.timestamp, s.prefs)) + "</p>" : empty("No intraday HR data.")) + "</article>" +
                "<article class=\"card span-4\"><h4>Sleep</h4>" + (sl ? "<p class=\"metric\">" + esc(u.formatDurationMs(sl.duration_ms)) + "</p><p class=\"metric-sub\">Bed " + esc(u.formatTime(sl.start_time, s.prefs)) + " | Wake " + esc(u.formatTime(sl.end_time, s.prefs)) + "</p>" : empty("No sleep data for this date.")) + "</article>" +
                "<article class=\"card span-4\"><h4>Active Calories</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(activeCalories))) + " kcal</p><p class=\"metric-sub\">Today</p></article>" +
                "<article class=\"card span-4\"><h4>Distance</h4><p class=\"metric\">" + esc(u.formatDistanceMeters(distanceMeters)) + "</p><p class=\"metric-sub\">Today total</p></article>" +
                "<article class=\"card span-4\"><h4>Total Calories</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(totalCalories))) + " kcal</p><p class=\"metric-sub\">Today burn</p></article>" +
                "<article class=\"card span-6\"><h4>Resting HR</h4>" + (rhr ? "<p class=\"metric\">" + esc(rhr.bpm) + " bpm</p><p class=\"metric-sub\">" + esc(rhr.date) + "</p>" : "<div class=\"empty-state\"><strong>Not available</strong><p>No resting HR for today.</p><a class=\"hint-link\" href=\"#\" data-reason=\"resting-hr\">Not available - Why?</a></div>") + "</article>" +
                "<article class=\"card span-6\"><h4>Cardio Snapshot</h4><p class=\"metric-sub\">" + (latestSpO2 ? ("SpO2 " + esc((Number(latestSpO2.percentage) * 100).toFixed(1)) + "%") : "SpO2 unavailable") + "</p><p class=\"metric-sub\">" + (latestHrv ? ("HRV RMSSD " + esc(Number(latestHrv.rmssd).toFixed(1)) + " ms") : "HRV unavailable") + "</p></article>" +
                "<article class=\"card span-6\"><h4>Nutrition Snapshot</h4>" + (latestNutrition ? "<p class=\"metric\">" + esc(u.formatNumber(Math.round(Number(latestNutrition.energy_kcal || 0)))) + " kcal</p><p class=\"metric-sub\">Protein " + esc(u.formatNumber(Math.round(Number(latestNutrition.protein_grams || 0)))) + "g | Carbs " + esc(u.formatNumber(Math.round(Number(latestNutrition.carbs_grams || 0)))) + "g | Fat " + esc(u.formatNumber(Math.round(Number(latestNutrition.fat_grams || 0)))) + "g</p>" : empty("No nutrition record for latest interval.")) + "</article>" +
                "<article class=\"card span-6\"><h4>Data Freshness</h4><div class=\"freshness-list\"><div class=\"freshness-item\"><span>Steps</span><span>" + esc(fresh.steps) + "</span></div><div class=\"freshness-item\"><span>Sleep</span><span>" + esc(fresh.sleep) + "</span></div><div class=\"freshness-item\"><span>Heart Rate</span><span>" + esc(fresh.heartRate) + "</span></div><div class=\"freshness-item\"><span>Resting HR</span><span>" + esc(fresh.restingHeartRate) + "</span></div><div class=\"freshness-item\"><span>Active Calories</span><span>" + esc(fresh.activeCalories) + "</span></div><div class=\"freshness-item\"><span>Distance</span><span>" + esc(fresh.distance) + "</span></div><div class=\"freshness-item\"><span>Total Calories</span><span>" + esc(fresh.totalCalories) + "</span></div><div class=\"freshness-item\"><span>Nutrition</span><span>" + esc(fresh.nutrition) + "</span></div><div class=\"freshness-item\"><span>SpO2</span><span>" + esc(fresh.oxygenSaturation) + "</span></div><div class=\"freshness-item\"><span>HRV</span><span>" + esc(fresh.hrv) + "</span></div></div></article>" +
                "</section>";

            byId("todaySync").addEventListener("click", async function () {
                try {
                    setStatus("Sync requested...");
                    await api.triggerSync({ automatic: false });
                    for (var i = 0; i < 7; i += 1) {
                        await new Promise(function (resolve) { global.setTimeout(resolve, 2000); });
                        updateLastSynced(data(await api.getSyncStatus(true)));
                    }
                    setStatus("Sync completed.");
                    renderToday(true, true);
                } catch (err) {
                    setStatus(err.message || "Sync failed.", "error");
                }
            });
            hintLinks();
        } catch (err) {
            if (silent) {
                return;
            }
            el.viewContainer.innerHTML = "<section class=\"error-state\"><h3>Unable to load today</h3><p>" + esc(err.message || "Unexpected error") + "</p></section>";
        }
    }

    function longest(sessions) {
        return u.safeArray(sessions).reduce(function reduce(acc, cur) {
            if (!acc) return cur;
            return Number(cur.duration_ms || 0) > Number(acc.duration_ms || 0) ? cur : acc;
        }, null);
    }

    async function renderSleep(force, silent) {
        if (!silent) {
            el.viewContainer.innerHTML = skeleton();
        }
        try {
            var day = s.date.sleep;
            var dayDate = new Date(day + "T00:00:00");
            var win = Number(s.prefs.sleepTrendWindow || 14);
            var start = u.shiftDate(new Date(), -(win - 1));
            var weekStart = u.shiftDate(new Date(), -6);

            var rows = await Promise.all([
                api.getSleep({ from: u.startOfDayIso(dayDate), to: u.endOfDayIso(dayDate), limit: 200, offset: 0 }, force),
                api.getAggregates({ type: "sleep", from: u.toDateInputValue(start), to: u.toDateInputValue(new Date()) }, force),
                api.getSleep({ from: u.startOfDayIso(weekStart), to: u.endOfDayIso(new Date()), limit: 200, offset: 0 }, force)
            ]);

            var sessions = u.safeArray(data(rows[0]));
            var aggs = u.safeArray(data(rows[1])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var week = u.safeArray(data(rows[2]));
            var chosen = longest(sessions);
            var bedVar = Math.round(u.varianceMinutes(week.map(function (r) { return r.start_time; })));
            var wakeVar = Math.round(u.varianceMinutes(week.map(function (r) { return r.end_time; })));

            el.viewContainer.innerHTML = "<section class=\"view-head\"><div><p class=\"eyebrow\">Sleep</p><h3>Recovery and consistency</h3></div></section>" +
                "<section class=\"controls\"><div class=\"control\"><label for=\"sleepDate\">Date</label><input id=\"sleepDate\" type=\"date\" value=\"" + esc(day) + "\"></div><div class=\"control\"><label for=\"sleepWindow\">Trend window</label><select id=\"sleepWindow\"><option value=\"7\" " + (win === 7 ? "selected" : "") + ">7 days</option><option value=\"14\" " + (win === 14 ? "selected" : "") + ">14 days</option><option value=\"30\" " + (win === 30 ? "selected" : "") + ">30 days</option></select></div></section>" +
                "<section class=\"card-grid\">" +
                "<article class=\"card span-4\"><h4>Selected Night</h4>" + (chosen ? "<p class=\"metric\">" + esc(u.formatDurationMs(chosen.duration_ms)) + "</p><p class=\"metric-sub\">Bed " + esc(u.formatTime(chosen.start_time, s.prefs)) + " | Wake " + esc(u.formatTime(chosen.end_time, s.prefs)) + "</p>" : empty("No sleep data for selected date.")) + "</article>" +
                "<article class=\"card span-4\"><h4>Weekly Consistency</h4><p class=\"metric\">+/-" + esc(bedVar) + "m</p><p class=\"metric-sub\">Bed variance | Wake variance +/-" + esc(wakeVar) + "m</p></article>" +
                "<article class=\"card span-4\"><h4>Stages Timeline</h4><div class=\"empty-state\"><strong>Not available</strong><p>Stages are not exposed yet.</p><a class=\"hint-link\" href=\"#\" data-reason=\"sleep\">Not available - Why?</a></div></article>" +
                "<article class=\"card span-6\"><h4>Sleep Sessions (Date)</h4><div class=\"chart-wrap\"><canvas id=\"sleepSessionChart\"></canvas></div></article>" +
                "<article class=\"card span-6\"><h4>" + esc(win) + "-Day Trend</h4><div class=\"chart-wrap\"><canvas id=\"sleepTrendChart\"></canvas></div></article>" +
                "</section>";

            byId("sleepDate").addEventListener("change", function (e) { s.date.sleep = e.target.value; renderSleep(true); });
            byId("sleepWindow").addEventListener("change", function (e) {
                s.prefs.sleepTrendWindow = Number(e.target.value);
                s.prefs = u.savePreferences(s.prefs);
                renderSleep(true);
            });

            if (sessions.length) {
                charts.horizontalDuration("sleepSessionChart", {
                    labels: sessions.map(function (r) { return u.formatTime(r.start_time, s.prefs); }),
                    values: sessions.map(function (r) { return Number(r.duration_ms || 0) / 3600000; }),
                    color: "#3f7858",
                    animate: !silent,
                    tickFormatter: function (v) { return v + "h"; }
                });
            } else {
                byId("sleepSessionChart").parentElement.innerHTML = empty("No sessions for timeline.");
            }

            if (aggs.length) {
                charts.line("sleepTrendChart", {
                    labels: aggs.map(function (r) { return r.date; }),
                    values: aggs.map(function (r) { return Number(r.value || 0) / 3600000; }),
                    label: "Sleep hours",
                    color: "#5f9b79",
                    animate: !silent,
                    fill: true,
                    tickFormatter: function (v) { return v + "h"; }
                });
            } else {
                byId("sleepTrendChart").parentElement.innerHTML = empty("No aggregate sleep trend data.");
            }

            hintLinks();
        } catch (err) {
            if (silent) {
                return;
            }
            el.viewContainer.innerHTML = "<section class=\"error-state\"><h3>Unable to load sleep</h3><p>" + esc(err.message || "Unexpected error") + "</p></section>";
        }
    }

    async function renderCardio(force, silent) {
        if (!silent) {
            el.viewContainer.innerHTML = skeleton();
        }
        try {
            var day = s.date.cardio;
            var dayDate = new Date(day + "T00:00:00");
            var win = Number(s.prefs.cardioTrendWindow || 14);
            var start = u.shiftDate(new Date(), -(win - 1));

            var rows = await Promise.all([
                api.getHeartRate({ from: u.startOfDayIso(dayDate), to: u.endOfDayIso(dayDate), limit: 2000, offset: 0 }, force),
                api.getRestingHeartRate({ from: u.toDateInputValue(start), to: u.toDateInputValue(new Date()), limit: 400, offset: 0 }, force),
                api.getHrv({ from: u.startOfDayIso(dayDate), to: u.endOfDayIso(dayDate), limit: 500, offset: 0 }, force),
                api.getOxygenSaturation({ from: u.startOfDayIso(dayDate), to: u.endOfDayIso(dayDate), limit: 500, offset: 0 }, force),
                api.getAggregates({ type: "hrv", from: u.toDateInputValue(start), to: u.toDateInputValue(new Date()) }, force),
                api.getAggregates({ type: "oxygen_saturation", from: u.toDateInputValue(start), to: u.toDateInputValue(new Date()) }, force)
            ]);

            var hr = u.safeArray(data(rows[0])).sort(function (a, b) { return String(a.timestamp).localeCompare(String(b.timestamp)); });
            var rhr = u.safeArray(data(rows[1])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var hrv = u.safeArray(data(rows[2])).sort(function (a, b) { return String(a.timestamp).localeCompare(String(b.timestamp)); });
            var spo2 = u.safeArray(data(rows[3])).sort(function (a, b) { return String(a.timestamp).localeCompare(String(b.timestamp)); });
            var hrvTrend = u.safeArray(data(rows[4])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var spo2Trend = u.safeArray(data(rows[5])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var latest = hr.length ? hr[hr.length - 1] : null;
            var latestHrv = hrv.length ? hrv[hrv.length - 1] : null;
            var latestSpO2 = spo2.length ? spo2[spo2.length - 1] : null;

            el.viewContainer.innerHTML = "<section class=\"view-head\"><div><p class=\"eyebrow\">Cardio</p><h3>Intraday and resting trends</h3></div></section>" +
                "<section class=\"controls\"><div class=\"control\"><label for=\"cardioDate\">Intraday date</label><input id=\"cardioDate\" type=\"date\" value=\"" + esc(day) + "\"></div><div class=\"control\"><label for=\"cardioWindow\">Resting trend</label><select id=\"cardioWindow\"><option value=\"7\" " + (win === 7 ? "selected" : "") + ">7 days</option><option value=\"14\" " + (win === 14 ? "selected" : "") + ">14 days</option><option value=\"30\" " + (win === 30 ? "selected" : "") + ">30 days</option></select></div></section>" +
                "<section class=\"card-grid\">" +
                "<article class=\"card span-4\"><h4>Latest Reading</h4>" + (latest ? "<p class=\"metric\">" + esc(latest.bpm) + " bpm</p><p class=\"metric-sub\">" + esc(u.formatDateTime(latest.timestamp, s.prefs)) + "</p>" : empty("No heart-rate data for date.")) + "</article>" +
                "<article class=\"card span-4\"><h4>Resting HR</h4>" + (rhr.length ? "<p class=\"metric\">" + esc(rhr[rhr.length - 1].bpm) + " bpm</p><p class=\"metric-sub\">Trend window</p>" : empty("No resting trend data.")) + "</article>" +
                "<article class=\"card span-4\"><h4>HRV</h4>" + (latestHrv ? "<p class=\"metric\">" + esc(Number(latestHrv.rmssd || 0).toFixed(1)) + " ms</p><p class=\"metric-sub\">" + esc(u.formatDateTime(latestHrv.timestamp, s.prefs)) + "</p>" : "<div class=\"empty-state\"><strong>Not available</strong><p>No HRV points for selected date.</p><a class=\"hint-link\" href=\"#\" data-reason=\"hrv\">Not available - Why?</a></div>") + "</article>" +
                "<article class=\"card span-4\"><h4>SpO2</h4>" + (latestSpO2 ? "<p class=\"metric\">" + esc((Number(latestSpO2.percentage || 0) * 100).toFixed(1)) + "%</p><p class=\"metric-sub\">" + esc(u.formatDateTime(latestSpO2.timestamp, s.prefs)) + "</p>" : empty("No SpO2 points for selected date.")) + "</article>" +
                "<article class=\"card span-6\"><h4>Intraday Heart Rate</h4><div class=\"chart-wrap\"><canvas id=\"cardioHrChart\"></canvas></div></article>" +
                "<article class=\"card span-6\"><h4>Resting HR Trend</h4><div class=\"chart-wrap\"><canvas id=\"cardioRestChart\"></canvas></div></article>" +
                "<article class=\"card span-6\"><h4>HRV Trend</h4><div class=\"chart-wrap\"><canvas id=\"cardioHrvChart\"></canvas></div></article>" +
                "<article class=\"card span-6\"><h4>SpO2 Trend</h4><div class=\"chart-wrap\"><canvas id=\"cardioSpO2Chart\"></canvas></div></article>" +
                "</section>";

            byId("cardioDate").addEventListener("change", function (e) { s.date.cardio = e.target.value; renderCardio(true); });
            byId("cardioWindow").addEventListener("change", function (e) {
                s.prefs.cardioTrendWindow = Number(e.target.value);
                s.prefs = u.savePreferences(s.prefs);
                renderCardio(true);
            });

            if (hr.length) {
                charts.line("cardioHrChart", {
                    labels: hr.map(function (r) { return u.formatTime(r.timestamp, s.prefs); }),
                    values: hr.map(function (r) { return Number(r.bpm || 0); }),
                    label: "BPM",
                    animate: !silent,
                    fill: true
                });
            } else {
                byId("cardioHrChart").parentElement.innerHTML = empty("No intraday HR points.");
            }

            if (rhr.length) {
                charts.line("cardioRestChart", {
                    labels: rhr.map(function (r) { return r.date; }),
                    values: rhr.map(function (r) { return Number(r.bpm || 0); }),
                    animate: !silent,
                    label: "Resting BPM"
                });
            } else {
                byId("cardioRestChart").parentElement.innerHTML = empty("No resting HR points.");
            }

            if (hrvTrend.length) {
                charts.line("cardioHrvChart", {
                    labels: hrvTrend.map(function (r) { return r.date; }),
                    values: hrvTrend.map(function (r) { return Number(r.value || 0); }),
                    animate: !silent,
                    label: "RMSSD (ms)"
                });
            } else {
                byId("cardioHrvChart").parentElement.innerHTML = empty("No HRV trend data.");
            }

            if (spo2Trend.length) {
                charts.line("cardioSpO2Chart", {
                    labels: spo2Trend.map(function (r) { return r.date; }),
                    values: spo2Trend.map(function (r) { return Number(r.value || 0) * 100; }),
                    animate: !silent,
                    label: "SpO2 (%)",
                    tickFormatter: function (value) { return Number(value).toFixed(0) + "%"; }
                });
            } else {
                byId("cardioSpO2Chart").parentElement.innerHTML = empty("No SpO2 trend data.");
            }

            hintLinks();
        } catch (err) {
            if (silent) {
                return;
            }
            el.viewContainer.innerHTML = "<section class=\"error-state\"><h3>Unable to load cardio</h3><p>" + esc(err.message || "Unexpected error") + "</p></section>";
        }
    }

    function sum(rows) {
        return u.safeArray(rows).reduce(function (total, row) { return total + Number(row.value || 0); }, 0);
    }

    async function renderActivity(force, silent) {
        if (!silent) {
            el.viewContainer.innerHTML = skeleton();
        }
        try {
            var win = Number(s.prefs.activityTrendWindow || 14);
            var now = new Date();
            var curStart = u.shiftDate(now, -(win - 1));
            var prevStart = u.shiftDate(curStart, -win);
            var prevEnd = u.shiftDate(curStart, -1);

            var rows = await Promise.all([
                api.getAggregates({ type: "steps", from: u.toDateInputValue(curStart), to: u.toDateInputValue(now) }, force),
                api.getAggregates({ type: "steps", from: u.toDateInputValue(prevStart), to: u.toDateInputValue(prevEnd) }, force),
                api.getAggregates({ type: "active_calories", from: u.toDateInputValue(curStart), to: u.toDateInputValue(now) }, force),
                api.getAggregates({ type: "distance", from: u.toDateInputValue(curStart), to: u.toDateInputValue(now) }, force)
            ]);

            var cur = u.safeArray(data(rows[0])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var prev = u.safeArray(data(rows[1]));
            var activeCalories = u.safeArray(data(rows[2])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var distance = u.safeArray(data(rows[3])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var total = sum(cur);
            var prevTotal = sum(prev);
            var avg = cur.length ? total / cur.length : 0;
            var activeCaloriesTotal = sum(activeCalories);
            var distanceMetersTotal = sum(distance);
            var dir = u.trendDirection(total, prevTotal);
            var arrow = dir === "up" ? "UP" : dir === "down" ? "DOWN" : "FLAT";

            el.viewContainer.innerHTML = "<section class=\"view-head\"><div><p class=\"eyebrow\">Activity</p><h3>Steps, distance, and calories</h3></div></section>" +
                "<section class=\"controls\"><div class=\"control\"><label for=\"activityWindow\">Window</label><select id=\"activityWindow\"><option value=\"7\" " + (win === 7 ? "selected" : "") + ">7 days</option><option value=\"14\" " + (win === 14 ? "selected" : "") + ">14 days</option><option value=\"30\" " + (win === 30 ? "selected" : "") + ">30 days</option></select></div></section>" +
                "<section class=\"card-grid\">" +
                "<article class=\"card span-4\"><h4>Total</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(total))) + "</p><p class=\"metric-sub\">Last " + esc(win) + " days</p></article>" +
                "<article class=\"card span-4\"><h4>Daily Average</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(avg))) + "</p><p class=\"metric-sub\">steps/day</p></article>" +
                "<article class=\"card span-4\"><h4>Trend</h4><p class=\"metric\">" + esc(arrow) + "</p><p class=\"metric-sub\">" + esc(u.trendLabel(dir)) + " vs previous period</p></article>" +
                "<article class=\"card span-6\"><h4>Active Calories</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(activeCaloriesTotal))) + " kcal</p><p class=\"metric-sub\">Window total</p></article>" +
                "<article class=\"card span-6\"><h4>Distance</h4><p class=\"metric\">" + esc(u.formatDistanceMeters(distanceMetersTotal)) + "</p><p class=\"metric-sub\">Window total</p></article>" +
                "<article class=\"card span-12\"><h4>Steps Per Day</h4><div class=\"chart-wrap\"><canvas id=\"activityChart\"></canvas></div></article>" +
                "<article class=\"card span-6\"><h4>Active Calories Trend</h4><div class=\"chart-wrap\"><canvas id=\"activityCaloriesChart\"></canvas></div></article>" +
                "<article class=\"card span-6\"><h4>Distance Trend</h4><div class=\"chart-wrap\"><canvas id=\"activityDistanceChart\"></canvas></div></article>" +
                "</section>";

            byId("activityWindow").addEventListener("change", function (e) {
                s.prefs.activityTrendWindow = Number(e.target.value);
                s.prefs = u.savePreferences(s.prefs);
                renderActivity(true);
            });

            if (cur.length) {
                charts.bar("activityChart", {
                    labels: cur.map(function (r) { return r.date; }),
                    values: cur.map(function (r) { return Number(r.value || 0); }),
                    animate: !silent,
                    label: "Steps"
                });
            } else {
                byId("activityChart").parentElement.innerHTML = empty("No step aggregates in this range.");
            }

            if (activeCalories.length) {
                charts.line("activityCaloriesChart", {
                    labels: activeCalories.map(function (r) { return r.date; }),
                    values: activeCalories.map(function (r) { return Number(r.value || 0); }),
                    animate: !silent,
                    label: "kcal",
                    fill: true
                });
            } else {
                byId("activityCaloriesChart").parentElement.innerHTML = empty("No active-calorie aggregates in this range.");
            }

            if (distance.length) {
                charts.line("activityDistanceChart", {
                    labels: distance.map(function (r) { return r.date; }),
                    values: distance.map(function (r) { return Number(r.value || 0) / 1000; }),
                    animate: !silent,
                    label: "km",
                    fill: true,
                    tickFormatter: function (value) { return Number(value).toFixed(1) + " km"; }
                });
            } else {
                byId("activityDistanceChart").parentElement.innerHTML = empty("No distance aggregates in this range.");
            }
        } catch (err) {
            if (silent) {
                return;
            }
            el.viewContainer.innerHTML = "<section class=\"error-state\"><h3>Unable to load activity</h3><p>" + esc(err.message || "Unexpected error") + "</p></section>";
        }
    }

    async function renderNutrition(force, silent) {
        if (!silent) {
            el.viewContainer.innerHTML = skeleton();
        }
        try {
            var day = s.date.nutrition;
            var dayDate = new Date(day + "T00:00:00");
            var win = Number(s.prefs.nutritionTrendWindow || 14);
            var start = u.shiftDate(new Date(), -(win - 1));

            var rows = await Promise.all([
                api.getNutrition({ from: u.startOfDayIso(dayDate), to: u.endOfDayIso(dayDate), limit: 500, offset: 0 }, force),
                api.getAggregates({ type: "nutrition", from: u.toDateInputValue(start), to: u.toDateInputValue(new Date()) }, force)
            ]);

            var entries = u.safeArray(data(rows[0]));
            var trend = u.safeArray(data(rows[1])).sort(function (a, b) { return String(a.date).localeCompare(String(b.date)); });
            var totals = entries.reduce(function (acc, row) {
                acc.calories += Number(row.energy_kcal || 0);
                acc.protein += Number(row.protein_grams || 0);
                acc.carbs += Number(row.carbs_grams || 0);
                acc.fat += Number(row.fat_grams || 0);
                return acc;
            }, { calories: 0, protein: 0, carbs: 0, fat: 0 });

            var macroChart = entries.length
                ? "<div class=\"chart-wrap\"><canvas id=\"nutritionMacroChart\"></canvas></div>"
                : empty("No nutrition entries on selected date.");
            var latestMeal = entries.length ? entries[entries.length - 1] : null;

            el.viewContainer.innerHTML = "<section class=\"view-head\"><div><p class=\"eyebrow\">Nutrition</p><h3>Calories and macros</h3></div></section>" +
                "<section class=\"controls\"><div class=\"control\"><label for=\"nutritionDate\">Date</label><input id=\"nutritionDate\" type=\"date\" value=\"" + esc(day) + "\"></div><div class=\"control\"><label for=\"nutritionWindow\">Trend window</label><select id=\"nutritionWindow\"><option value=\"7\" " + (win === 7 ? "selected" : "") + ">7 days</option><option value=\"14\" " + (win === 14 ? "selected" : "") + ">14 days</option><option value=\"30\" " + (win === 30 ? "selected" : "") + ">30 days</option></select></div></section>" +
                "<section class=\"card-grid\">" +
                "<article class=\"card span-3\"><h4>Calories</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(totals.calories))) + "</p><p class=\"metric-sub\">kcal</p></article>" +
                "<article class=\"card span-3\"><h4>Protein</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(totals.protein))) + "g</p><p class=\"metric-sub\">daily</p></article>" +
                "<article class=\"card span-3\"><h4>Carbs</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(totals.carbs))) + "g</p><p class=\"metric-sub\">daily</p></article>" +
                "<article class=\"card span-3\"><h4>Fat</h4><p class=\"metric\">" + esc(u.formatNumber(Math.round(totals.fat))) + "g</p><p class=\"metric-sub\">daily</p></article>" +
                "<article class=\"card span-6\"><h4>Macro Breakdown</h4>" + macroChart + "</article>" +
                "<article class=\"card span-6\"><h4>Calorie Trend</h4><div class=\"chart-wrap\"><canvas id=\"nutritionTrendChart\"></canvas></div></article>" +
                "<article class=\"card span-12\"><h4>Latest Entry</h4>" + (latestMeal ? ("<p class=\"metric-sub\">" + esc(latestMeal.meal_type || "Meal") + " | " + esc(u.formatDateTime(latestMeal.start_time, s.prefs)) + "</p><p class=\"metric-sub\">Calories " + esc(u.formatNumber(Math.round(Number(latestMeal.energy_kcal || 0)))) + " kcal, Protein " + esc(u.formatNumber(Math.round(Number(latestMeal.protein_grams || 0)))) + "g, Carbs " + esc(u.formatNumber(Math.round(Number(latestMeal.carbs_grams || 0)))) + "g, Fat " + esc(u.formatNumber(Math.round(Number(latestMeal.fat_grams || 0)))) + "g</p>") : "<div class=\"empty-state\"><strong>No entries</strong><p>No nutrition rows in selected range.</p></div>") + "</article>" +
                "</section>";

            byId("nutritionDate").addEventListener("change", function (e) { s.date.nutrition = e.target.value; renderNutrition(true); });
            byId("nutritionWindow").addEventListener("change", function (e) {
                s.prefs.nutritionTrendWindow = Number(e.target.value);
                s.prefs = u.savePreferences(s.prefs);
                renderNutrition(true);
            });

            if (entries.length) {
                charts.bar("nutritionMacroChart", {
                    labels: ["Protein", "Carbs", "Fat"],
                    values: [totals.protein, totals.carbs, totals.fat],
                    animate: !silent,
                    label: "grams"
                });
            }

            if (trend.length) {
                charts.line("nutritionTrendChart", {
                    labels: trend.map(function (r) { return r.date; }),
                    values: trend.map(function (r) { return Number(r.value || 0); }),
                    animate: !silent,
                    label: "kcal",
                    fill: true
                });
            } else {
                byId("nutritionTrendChart").parentElement.innerHTML = empty("No nutrition trend data.");
            }
        } catch (err) {
            if (silent) {
                return;
            }
            el.viewContainer.innerHTML = "<section class=\"error-state\"><h3>Unable to load nutrition</h3><p>" + esc(err.message || "Unexpected error") + "</p></section>";
        }
    }

    function badge(v) {
        return "<span class=\"pill\">" + esc(v || "unknown") + "</span>";
    }

    async function renderSettings(force, silent) {
        if (!silent) {
            el.viewContainer.innerHTML = skeleton();
        }
        try {
            var rows = await Promise.all([
                api.getSession(true),
                api.getPermissions(force),
                api.getServerInfo(force, true)
            ]);
            var sess = data(rows[0]) || {};
            var perm = data(rows[1]) || {};
            var info = data(rows[2]) || {};
            s.session = sess;
            var secureOn = !sess.open_access_enabled;
            var now = new Date();
            var defaultFrom = u.toDateInputValue(u.shiftDate(now, -29));
            var defaultTo = u.toDateInputValue(now);

            el.viewContainer.innerHTML = "<section class=\"view-head\"><div><p class=\"eyebrow\">Settings</p><h3>Preferences, security, exports</h3></div></section>" +
                "<section class=\"settings-grid\">" +
                "<article class=\"card\"><h4>Preferences</h4><div class=\"settings-row\"><label for=\"prefTime\">Time format</label><select id=\"prefTime\"><option value=\"12h\" " + (s.prefs.timeFormat === "12h" ? "selected" : "") + ">12-hour</option><option value=\"24h\" " + (s.prefs.timeFormat === "24h" ? "selected" : "") + ">24-hour</option></select><button id=\"savePrefs\" class=\"btn btn-primary\" type=\"button\">Save Preferences</button></div></article>" +
                "<article class=\"card\"><h4>Security</h4><div class=\"toggle-row\"><div><strong>Passcode protection</strong><p>Disable only on trusted LANs.</p></div><button id=\"securityToggle\" type=\"button\" class=\"toggle " + (secureOn ? "is-on" : "") + "\"></button></div><form id=\"passcodeForm\" class=\"settings-row\"><label for=\"currentPass\">Current passcode (optional)</label><input id=\"currentPass\" type=\"password\" inputmode=\"numeric\" maxlength=\"8\"><label for=\"newPass\">New passcode</label><input id=\"newPass\" type=\"password\" inputmode=\"numeric\" maxlength=\"8\" placeholder=\"4-8 digits\"><button class=\"btn btn-secondary\" type=\"submit\">Update Passcode</button></form></article>" +
                "<article class=\"card\"><h4>Export</h4><div class=\"settings-row\"><label for=\"expType\">Metric</label><select id=\"expType\"><option value=\"heart_rate\">Heart Rate</option><option value=\"sleep\">Sleep</option><option value=\"steps\">Steps</option><option value=\"resting_heart_rate\">Resting Heart Rate</option></select><label for=\"expFrom\">From</label><input id=\"expFrom\" type=\"date\" value=\"" + esc(defaultFrom) + "\"><label for=\"expTo\">To</label><input id=\"expTo\" type=\"date\" value=\"" + esc(defaultTo) + "\"><button id=\"expCsv\" class=\"btn btn-primary\" type=\"button\">Download CSV</button><button id=\"expAll\" class=\"btn btn-secondary\" type=\"button\">Export All Data</button></div></article>" +
                "<article class=\"card\"><h4>App Info</h4><div class=\"app-info\"><div><span>Version</span><span>" + esc(info.app_version || "--") + "</span></div><div><span>Phone</span><span>" + esc(info.phone_name || "--") + "</span></div><div><span>IP</span><span>" + esc(info.local_ip_address || "--") + "</span></div><div><span>Uptime</span><span>" + esc(info.uptime_seconds || 0) + "s</span></div><div><span>Clients</span><span>" + esc(info.connected_clients || 0) + "</span></div></div></article>" +
                "<article class=\"card span-12\"><h4>Permissions</h4><div class=\"freshness-list\"><div class=\"freshness-item\"><span>Heart Rate</span>" + badge(perm.heart_rate) + "</div><div class=\"freshness-item\"><span>Sleep</span>" + badge(perm.sleep) + "</div><div class=\"freshness-item\"><span>Steps</span>" + badge(perm.steps) + "</div><div class=\"freshness-item\"><span>Resting HR</span>" + badge(perm.resting_heart_rate) + "</div><div class=\"freshness-item\"><span>Active Calories</span>" + badge(perm.active_calories) + "</div><div class=\"freshness-item\"><span>Distance</span>" + badge(perm.distance) + "</div><div class=\"freshness-item\"><span>Total Calories</span>" + badge(perm.total_calories) + "</div><div class=\"freshness-item\"><span>Nutrition</span>" + badge(perm.nutrition) + "</div><div class=\"freshness-item\"><span>SpO2</span>" + badge(perm.oxygen_saturation) + "</div><div class=\"freshness-item\"><span>HRV</span>" + badge(perm.heart_rate_variability) + "</div><div class=\"freshness-item\"><span>History</span>" + badge(perm.history) + "</div></div></article>" +
                "</section>";

            byId("savePrefs").addEventListener("click", function () {
                s.prefs.timeFormat = byId("prefTime").value;
                s.prefs = u.savePreferences(s.prefs);
                setStatus("Preferences saved.");
            });
            byId("securityToggle").addEventListener("click", async function () {
                var currentOpen = Boolean(s.session && s.session.open_access_enabled);
                var enableOpen = !currentOpen;
                if (enableOpen && !global.confirm("Enable open access for anyone on your LAN?")) return;
                try {
                    await api.setOpenAccess(enableOpen, enableOpen);
                    setStatus(enableOpen ? "Open access enabled." : "Passcode protection enabled.");
                    renderSettings(true);
                } catch (err) {
                    setStatus(err.message || "Unable to update security.", "error");
                }
            });
            byId("passcodeForm").addEventListener("submit", async function (e) {
                e.preventDefault();
                var currentPass = byId("currentPass").value.trim();
                var newPass = byId("newPass").value.trim();
                if (!/^\d{4,8}$/.test(newPass)) {
                    setStatus("Passcode must be 4-8 digits.", "error");
                    return;
                }
                try {
                    await api.setPasscode(newPass, currentPass || null);
                    byId("currentPass").value = "";
                    byId("newPass").value = "";
                    setStatus("Passcode updated.");
                } catch (err) {
                    setStatus(err.message || "Failed to update passcode.", "error");
                }
            });
            byId("expCsv").addEventListener("click", function () {
                global.location.href = api.buildExportCsvUrl(byId("expType").value, byId("expFrom").value, byId("expTo").value);
            });
            byId("expAll").addEventListener("click", function () {
                global.location.href = api.buildExportAllUrl(byId("expFrom").value, byId("expTo").value);
            });
        } catch (err) {
            if (silent) {
                return;
            }
            el.viewContainer.innerHTML = "<section class=\"error-state\"><h3>Unable to load settings</h3><p>" + esc(err.message || "Unexpected error") + "</p></section>";
        }
    }

    async function render(force, silent) {
        if (!s.authed) return;
        if (s.rendering) return;
        s.rendering = true;
        charts.destroyAll();
        try {
            if (s.view === "today") {
                await renderToday(force, silent);
                return;
            }
            if (s.view === "sleep") {
                await renderSleep(force, silent);
                return;
            }
            if (s.view === "cardio") {
                await renderCardio(force, silent);
                return;
            }
            if (s.view === "activity") {
                await renderActivity(force, silent);
                return;
            }
            if (s.view === "nutrition") {
                await renderNutrition(force, silent);
                return;
            }
            await renderSettings(force, silent);
        } finally {
            s.rendering = false;
        }
    }

    function stopBackgroundUpdates() {
        if (s.timer.todayPoll) {
            global.clearInterval(s.timer.todayPoll);
            s.timer.todayPoll = null;
        }
        if (s.timer.autoSync) {
            global.clearInterval(s.timer.autoSync);
            s.timer.autoSync = null;
        }
        if (s.timer.viewRefresh) {
            global.clearInterval(s.timer.viewRefresh);
            s.timer.viewRefresh = null;
        }
    }

    function startBackgroundUpdates() {
        stopBackgroundUpdates();
        if (!s.authed) return;

        async function pollToday() {
            if (!s.authed) return;
            try {
                if (s.view === "today") {
                    await render(true, true);
                } else {
                    await api.getToday(true);
                    updateLastSynced(data(await api.getSyncStatus(true)));
                }
            } catch (_err) {}
        }

        pollToday();
        s.timer.todayPoll = global.setInterval(pollToday, 10000);

        s.timer.autoSync = global.setInterval(async function () {
            if (!s.authed) return;
            try {
                await api.triggerSync({ automatic: true });
            } catch (_err) {}
        }, 60000);

        s.timer.viewRefresh = global.setInterval(function () {
            if (!s.authed || s.view === "today") return;
            render(true, true);
        }, 60000);
    }

    function startPing() {
        if (s.timer.ping) {
            global.clearInterval(s.timer.ping);
            s.timer.ping = null;
        }
        async function ping() {
            try {
                await api.probeServer();
                setOffline(false);
                if (!s.authed && el.loginSubtitle) {
                    el.loginSubtitle.textContent = "Server online. Checking login session...";
                }
            } catch (_err) {
                setOffline(true);
                if (!s.authed && el.loginSubtitle) {
                    el.loginSubtitle.textContent = "Server is unreachable. Keep HelioLAN app open.";
                }
            }
        }
        ping();
        s.timer.ping = global.setInterval(ping, 30000);
    }

    function bind() {
        el.loginForm.addEventListener("submit", async function (e) {
            e.preventDefault();
            var passcode = el.passcodeInput.value.trim();
            if (!passcode) {
                setAuth("Passcode is required.", "error");
                return;
            }
            setAuth("Signing in...");
            try {
                await api.login(passcode);
                setAuth("Passcode accepted. Loading dashboard...", "success");
                el.passcodeInput.value = "";
                await checkSession(true);
            } catch (err) {
                setAuth(formatApiError(err, "Login failed."), "error");
            }
        });

        el.setPasscodeForm.addEventListener("submit", async function (e) {
            e.preventDefault();
            var passcode = el.newPasscodeInput.value.trim();
            if (!/^\d{4,8}$/.test(passcode)) {
                setAuth("Passcode must be 4-8 digits.", "error");
                return;
            }
            setAuth("Saving passcode...");
            try {
                await api.setPasscode(passcode, null);
                setAuth("Passcode saved. Signing in...", "success");
                el.newPasscodeInput.value = "";
                await checkSession(true);
            } catch (err) {
                setAuth(formatApiError(err, "Unable to set passcode."), "error");
            }
        });

        el.logoutButton.addEventListener("click", async function () {
            try { await api.logout(); } catch (_err) {}
            showLogin(true);
            setAuth("Signed out.", "success");
            setStatus("Signed out of dashboard.");
        });

        el.viewNav.addEventListener("click", function (evt) {
            var t = evt.target;
            if (!t || !t.matches(".nav-item")) return;
            s.view = t.getAttribute("data-view");
            markNav();
            global.location.hash = s.view;
            render(false);
        });

        global.addEventListener("hashchange", function () {
            if (!s.authed) return;
            var next = parseHash();
            if (next !== s.view) {
                s.view = next;
                markNav();
                render(false);
            }
        });
    }

    async function fetchVersion() {
        try {
            var info = data(await api.getServerInfo(true, true)) || {};
            el.appVersion.textContent = info.app_version ? "v" + info.app_version : "v-";
        } catch (_err) {
            el.appVersion.textContent = "v-";
        }
    }

    function start() {
        el.offlineBanner = byId("offlineBanner");
        el.loginScreen = byId("loginScreen");
        el.appShell = byId("appShell");
        el.loginForm = byId("loginForm");
        el.setPasscodeForm = byId("setPasscodeForm");
        el.passcodeInput = byId("passcodeInput");
        el.newPasscodeInput = byId("newPasscodeInput");
        el.loginSubtitle = byId("loginSubtitle");
        el.authMessage = byId("authMessage");
        el.appVersion = byId("appVersion");
        el.logoutButton = byId("logoutButton");
        el.viewNav = byId("viewNav");
        el.lastSyncedPill = byId("lastSyncedPill");
        el.globalStatus = byId("globalStatus");
        el.viewContainer = byId("viewContainer");

        var required = [
            "offlineBanner", "loginScreen", "appShell", "loginForm", "setPasscodeForm", "passcodeInput",
            "newPasscodeInput", "loginSubtitle", "authMessage", "appVersion", "logoutButton", "viewNav",
            "lastSyncedPill", "globalStatus", "viewContainer"
        ];
        var missingElements = required.filter(function (name) { return !el[name]; });
        if (missingElements.length) {
            reportBootstrapError(
                "Dashboard markup mismatch. Missing elements: " + missingElements.join(", ") + "."
            );
            return;
        }

        setVisible(el.loginScreen, true);
        setVisible(el.appShell, false);
        setVisible(el.loginForm, true);
        setVisible(el.setPasscodeForm, false);
        setAuth("Checking server status...");

        bind();
        startPing();
        fetchVersion();
        checkSession(true);
    }

    doc.addEventListener("DOMContentLoaded", start);
})(window, document);
