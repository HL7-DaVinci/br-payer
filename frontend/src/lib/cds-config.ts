import type { CdsServer } from "./fhir-config";

export type { CdsServer };

/**
 * Derive CDS server URL from FHIR server URL by removing /fhir path
 */
function deriveCdsServerFromFhir(fhirUrl: string): CdsServer {
  try {
    const url = new URL(fhirUrl);
    // Remove /fhir or /fhir/ from pathname
    url.pathname = url.pathname.replace(/\/fhir\/?$/, "") || "/";
    return {
      name: "Local CDS Server",
      url: url.toString().replace(/\/$/, ""), // Remove trailing slash
    };
  } catch {
    return {
      name: "Local CDS Server",
      url: "http://localhost:8080",
    };
  }
}

const DEFAULT_CDS_SERVERS: CdsServer[] = [
  {
    name: "Local CDS Server",
    url: "http://localhost:8080",
  },
];

function isValidCdsServer(server: unknown): server is CdsServer {
  return (
    typeof server === "object" &&
    server !== null &&
    typeof (server as CdsServer).name === "string" &&
    typeof (server as CdsServer).url === "string"
  );
}

function parseCdsServers(): CdsServer[] {
  // Priority 1: Runtime injection via window.APP_CONFIG
  if (
    window?.APP_CONFIG?.cdsServers &&
    Array.isArray(window.APP_CONFIG.cdsServers)
  ) {
    const servers = window.APP_CONFIG.cdsServers.filter(isValidCdsServer);
    if (servers.length > 0) {
      return servers;
    }
  }

  // Priority 2: Environment variable
  const envServers = import.meta.env.VITE_CDS_SERVERS;
  if (envServers) {
    try {
      const parsed = JSON.parse(envServers);
      if (Array.isArray(parsed)) {
        const servers = parsed.filter(isValidCdsServer);
        if (servers.length > 0) {
          return servers;
        }
      }
    } catch {
      console.warn("Failed to parse VITE_CDS_SERVERS, using defaults");
    }
  }

  // Priority 3: Derive from FHIR server or use defaults
  const fhirServersEnv = import.meta.env.VITE_FHIR_SERVERS;
  if (fhirServersEnv) {
    try {
      const parsed = JSON.parse(fhirServersEnv);
      if (Array.isArray(parsed) && parsed.length > 0 && parsed[0]?.url) {
        return [deriveCdsServerFromFhir(parsed[0].url)];
      }
    } catch {
      // Fall through to defaults
    }
  }

  return DEFAULT_CDS_SERVERS;
}

export const CDS_SERVERS: CdsServer[] = parseCdsServers();

const STORAGE_KEY = "cds-server-url";

export function getStoredCdsServerUrl(): string {
  if (typeof window === "undefined") {
    return CDS_SERVERS[0]?.url ?? "http://localhost:8080";
  }
  return (
    (localStorage.getItem(STORAGE_KEY) || CDS_SERVERS[0]?.url) ??
    "http://localhost:8080"
  );
}

export function setStoredCdsServerUrl(url: string): void {
  if (typeof window !== "undefined") {
    localStorage.setItem(STORAGE_KEY, url);
  }
}

export function getCdsServerByUrl(url: string): CdsServer | undefined {
  return CDS_SERVERS.find((server) => server.url === url);
}
