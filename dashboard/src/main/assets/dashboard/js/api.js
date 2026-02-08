// HelioLAN Dashboard API Client - Placeholder
console.log('HelioLAN Dashboard - API module loaded');

// TODO: Implement API client in Phase 8
class ApiClient {
    constructor(baseUrl) {
        this.baseUrl = baseUrl || window.location.origin;
    }

    async get(endpoint) {
        const response = await fetch(`${this.baseUrl}${endpoint}`);
        return response.json();
    }

    async post(endpoint, data) {
        const response = await fetch(`${this.baseUrl}${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        return response.json();
    }
}

const api = new ApiClient();
