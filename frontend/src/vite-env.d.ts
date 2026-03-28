/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * JSON array of FHIR server configurations.
   * Example: [{"name": "Local Server", "url": "http://localhost:8081/fhir"}]
   */
  readonly VITE_FHIR_SERVERS?: string;

  /**
   * JSON array of CDS server configurations.
   * Example: [{"name": "Local CDS", "url": "http://localhost:8081"}]
   */
  readonly VITE_CDS_SERVERS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
