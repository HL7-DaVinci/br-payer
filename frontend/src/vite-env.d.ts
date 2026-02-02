/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * JSON array of FHIR server configurations.
   * Example: [{"name": "Local Server", "url": "http://localhost:8080/fhir"}]
   */
  readonly VITE_FHIR_SERVERS?: string;

  /**
   * JSON array of CDS server configurations.
   * Example: [{"name": "Local CDS", "url": "http://localhost:8080"}]
   */
  readonly VITE_CDS_SERVERS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
