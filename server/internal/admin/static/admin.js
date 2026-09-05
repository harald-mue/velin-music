(() => {
	"use strict";

	const statuses = new Set(["running", "completed", "failed", "cancelled"]);
	const exactTimeFormatter = new Intl.DateTimeFormat(undefined, {
		year: "numeric",
		month: "short",
		day: "2-digit",
		hour: "2-digit",
		minute: "2-digit",
		second: "2-digit",
		timeZoneName: "short",
	});
	const shortDateFormatter = new Intl.DateTimeFormat(undefined, {
		month: "short",
		day: "numeric",
	});
	const shortDateWithYearFormatter = new Intl.DateTimeFormat(undefined, {
		year: "numeric",
		month: "short",
		day: "numeric",
	});
	const relativeTimeFormatter = new Intl.RelativeTimeFormat(undefined, {
		numeric: "auto",
		style: "short",
	});
	const browserTimeZone = exactTimeFormatter.resolvedOptions().timeZone;

	function parseTime(value) {
		let parsed = new Date(value);
		if (!Number.isNaN(parsed.getTime())) return parsed;
		parsed = new Date(value.replace(/(\.\d{3})\d+(?=Z|[+-]\d{2}:\d{2}$)/, "$1"));
		return parsed;
	}

	function compactTime(parsed) {
		const seconds = (parsed.getTime() - Date.now()) / 1000;
		const absoluteSeconds = Math.abs(seconds);
		if (absoluteSeconds < 45) return relativeTimeFormatter.format(0, "second");
		if (absoluteSeconds < 45 * 60) return relativeTimeFormatter.format(Math.round(seconds / 60), "minute");
		if (absoluteSeconds < 22 * 3600) return relativeTimeFormatter.format(Math.round(seconds / 3600), "hour");
		if (absoluteSeconds < 7 * 86400) return relativeTimeFormatter.format(Math.round(seconds / 86400), "day");
		if (parsed.getFullYear() === new Date().getFullYear()) return shortDateFormatter.format(parsed);
		return shortDateWithYearFormatter.format(parsed);
	}

	function localizeTime(node, value) {
		if (!value) return;
		const parsed = parseTime(value);
		if (Number.isNaN(parsed.getTime())) return;
		node.textContent = compactTime(parsed);
		const exact = exactTimeFormatter.format(parsed);
		node.title = browserTimeZone ? `${exact} · ${browserTimeZone}` : exact;
	}

	function refreshTimes() {
		for (const node of document.querySelectorAll("time[data-local-time]")) {
			localizeTime(node, node.getAttribute("datetime"));
		}
	}

	refreshTimes();
	window.setInterval(refreshTimes, 30000);

	function browserServerBaseURL() {
		const current = new URL(window.location.href);
		if (current.protocol !== "http:" && current.protocol !== "https:") return "";
		const adminMarker = "/admin/";
		const adminIndex = current.pathname.lastIndexOf(adminMarker);
		if (adminIndex >= 0) {
			current.pathname = current.pathname.slice(0, adminIndex) || "/";
		} else if (current.pathname.endsWith("/admin")) {
			current.pathname = current.pathname.slice(0, -"/admin".length) || "/";
		} else {
			current.pathname = "/";
		}
		current.username = "";
		current.password = "";
		current.search = "";
		current.hash = "";
		return current.toString().replace(/\/$/, "");
	}

	const serverBaseURL = browserServerBaseURL();
	const serverURLInput = document.querySelector("input[data-browser-origin-default]");
	if (serverURLInput && serverBaseURL) {
		serverURLInput.value = serverBaseURL;
	}

	function serverEndpoint(path) {
		return `${serverBaseURL}/${path.replace(/^\/+/, "")}`;
	}

	const monitor = document.querySelector("[data-scan-monitor]");
	if (!monitor) return;

	let timer = 0;
	let failures = 0;
	let requestRunning = false;

	function setText(container, field, value) {
		const node = container.querySelector(`[data-scan-field="${field}"]`);
		if (node) node.textContent = value;
	}

	function setTime(container, field, value) {
		const node = container.querySelector(`[data-scan-field="${field}"]`);
		if (!node) return;
		if (!value) {
			node.removeAttribute("datetime");
			node.removeAttribute("title");
			node.textContent = "—";
			return;
		}
		node.setAttribute("datetime", value);
		localizeTime(node, value);
	}

	function setStatus(container, value) {
		if (!statuses.has(value)) return;
		const node = container.querySelector('[data-scan-field="status"]');
		if (!node) return;
		for (const status of statuses) node.classList.remove(`status-${status}`);
		node.classList.add(`status-${value}`);
		node.textContent = value;
		if (value === "completed") {
			node.setAttribute("aria-label", "completed");
			node.title = "Completed";
		} else {
			node.removeAttribute("aria-label");
			node.removeAttribute("title");
		}
	}

	function updateScan(container, scan) {
		setStatus(container, scan.status);
		setText(container, "seen", String(scan.files_seen));
		setText(container, "indexed", String(scan.files_indexed));
		setText(container, "removed", String(scan.files_removed));
		setTime(container, "finished", scan.finished_at);
	}

	function scanContainer(id) {
		return Array.from(document.querySelectorAll("[data-scan-id]"))
			.find((node) => node.dataset.scanId === id);
	}

	function rootContainer(rootID, scanID) {
		return Array.from(document.querySelectorAll("[data-root-id]"))
			.find((node) => node.dataset.rootId === rootID && node.dataset.latestScanId === scanID);
	}

	async function fetchJSON(url) {
		const response = await fetch(url, {
			cache: "no-store",
			credentials: "same-origin",
			headers: { Accept: "application/json" },
		});
		if (!response.ok) throw new Error(`scan status request failed: ${response.status}`);
		return response.json();
	}

	async function pollList() {
		const payload = await fetchJSON(serverEndpoint("api/v1/admin/scans?limit=10"));
		const items = Array.isArray(payload.items) ? payload.items : [];
		const running = items.find((scan) => scan.status === "running");
		const previouslyRunning = monitor.dataset.scanRunning === "true";

		for (const scan of items) {
			const row = scanContainer(scan.id);
			if (row) updateScan(row, scan);
			const root = rootContainer(scan.root_id, scan.id);
			if (root) updateScan(root, scan);
		}

		const activity = document.querySelector("[data-scan-activity]");
		if (activity) {
			activity.hidden = !running;
			if (running) updateScan(activity, running);
		}
		for (const button of document.querySelectorAll("[data-scan-trigger]")) {
			button.disabled = Boolean(running);
		}

		if (running && !scanContainer(running.id)) {
			window.location.reload();
			return;
		}
		if (previouslyRunning && !running) {
			window.setTimeout(() => window.location.reload(), 300);
			return;
		}
		monitor.dataset.scanRunning = running ? "true" : "false";
	}

	async function pollDetail() {
		const scanID = monitor.dataset.scanId;
		if (!scanID) return;
		const scan = await fetchJSON(serverEndpoint(`api/v1/admin/scans/${encodeURIComponent(scanID)}`));
		updateScan(monitor, scan);
		if (scan.status !== "running") {
			window.location.reload();
		}
	}

	async function poll() {
		if (requestRunning || document.hidden) {
			schedule(1500);
			return;
		}
		requestRunning = true;
		try {
			if (monitor.dataset.scanMonitor === "detail") {
				await pollDetail();
			} else {
				await pollList();
			}
			failures = 0;
		} catch (_) {
			failures += 1;
		} finally {
			requestRunning = false;
		}
		schedule(Math.min(15000, 1500 * (2 ** Math.min(failures, 3))));
	}

	function schedule(delay) {
		window.clearTimeout(timer);
		timer = window.setTimeout(poll, delay);
	}

	document.addEventListener("visibilitychange", () => {
		if (!document.hidden) schedule(0);
	});
	if (monitor.dataset.scanMonitor === "detail" || monitor.dataset.scanRunning === "true") {
		schedule(500);
	}
})();
