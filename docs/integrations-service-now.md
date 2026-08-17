# ServiceNow CMDB federated read — PGM-10-E06 phase 2

## Scope

`components.adapters.service-now` adds the second provider-specific slice of PGM-10-E06. The adapter is deliberately limited to **federated read** against the ServiceNow Table API for `cmdb_ci` or one explicit `cmdb_ci_*` subclass. ServiceNow remains the external authority; InfraNexum does not create, update, delete, reconcile or persist remote configuration items in RSOT/ITAM in this phase.

The supported product contract is intentionally smaller than the full ServiceNow Table API:

- one configured `*.service-now.com` instance hostname per connector;
- one configured CMDB CI table (`cmdb_ci` or `cmdb_ci_*`) per connector;
- health verification through a one-row bounded read;
- name search only, with a 1–256 character allow-list (`A-Z`, `a-z`, digits, space, `- _ . / :`);
- offset pagination with `0 <= offset <= 1,000,000` and `1 <= limit <= 200`;
- projection limited to `sys_id`, `name`, `sys_class_name` and `sys_updated_on`;
- no arbitrary provider encoded query supplied by an InfraNexum caller.

## Governance and trust boundary

Each connector declares:

- provider: `service-now`;
- direction: `FEDERATED_READ`;
- authority: `EXTERNAL`.

The adapter depends only on the Integrations domain and Core contracts. It has no dependency on RSOT or ITAM. Any future import, reconciliation, write-back, ownership change or rollback contract is a separate PGM-10-E06 tranche and must not be inferred from this read-only surface.

## Authentication and secrets

InfraNexum accepts only a pre-provisioned bearer token referenced by `env:` or `file:`. No bearer token, OAuth client secret, refresh token or ServiceNow credential is persisted in product configuration or returned to the browser.

A machine identity should be provisioned in ServiceNow with the minimum Table API/ACL rights needed to read the configured CMDB table. Token acquisition and renewal remain external to InfraNexum until the product Secret Service/PKI/KMS boundary is delivered. For backend integrations, ServiceNow supports OAuth flows including Client Credentials; that is the recommended source of the bearer token when compatible with the target instance's policy.

Example server configuration:

```yaml
infranexum:
  integrations:
    service-now:
      maximum-response-bytes: 2097152
      connectors:
        cmdb-production:
          instance-host: example.service-now.com
          table-name: cmdb_ci
          bearer-token-reference: file:/run/secrets/infranexum-service-now-token
          request-timeout: PT15S
          enabled: true
```

The example hostname and secret path are non-production examples. The secret file itself must be provisioned outside the repository and mounted with least privilege.

## Provider request policy

The Server constructs Table API requests itself. A caller cannot submit `sysparm_query` directly. Search is normalized to:

```text
nameLIKE<validated-term>^ORDERBYsys_id
```

The request also sets:

```text
sysparm_fields=sys_id,name,sys_class_name,sys_updated_on
sysparm_limit=<bounded-limit>
sysparm_offset=<bounded-offset>
sysparm_exclude_reference_link=true
sysparm_display_value=false
sysparm_no_count=true
```

This policy is important because ServiceNow encoded queries are a provider-specific expression language and invalid query fragments can be handled according to instance configuration. InfraNexum therefore exposes a narrow search term rather than a general encoded-query proxy.

## Network and resilience controls

The JDK transport enforces:

- HTTPS only;
- destination host suffix `.service-now.com`;
- no userinfo or explicit port in the provider URI;
- `HttpClient.Redirect.NEVER`;
- per-connector request timeout in `(0s, 60s]`;
- bounded response bodies: 2 MiB by default, configurable up to 8 MiB;
- 401/403 mapped to provider authentication failure;
- 429 mapped to rate limiting;
- 5xx mapped to provider unavailability;
- provider payloads excluded from public error details.

ServiceNow documents `sysparm_limit` and `sysparm_offset` for Table API pagination and cautions that large limits can have a performance impact. InfraNexum applies a stricter product-side maximum of 200 records per page.

## InfraNexum API

All operations require capability `integrations.connectors` and permission `integrations.connector.read`:

```text
GET  /api/v1/integrations/providers/service-now
GET  /api/v1/integrations/providers/service-now/{connectorKey}/health
POST /api/v1/integrations/providers/service-now/{connectorKey}/configuration-items/search
```

The list and search operations use bounded offset pagination. The POST search is semantically repeatable and is not a provider mutation.

## Web behavior

The existing `Integrations` workspace gains a ServiceNow section only when `integrations.connectors` is available. The browser talks only to the InfraNexum Server over the authenticated same-origin session, uses CSRF protection for POST, and never receives or constructs the provider bearer token.

The workspace provides:

- configured connector catalogue;
- health check action;
- bounded name search;
- paginated minimal CMDB result table;
- DE/EN/ES/FR/IT localized labels and states.

## Operational verification

After configuring a non-production ServiceNow connector, verify:

1. Server startup fails explicitly if an enabled connector references an unreadable/missing token source.
2. The connector catalogue is visible only to an actor with `integrations.connector.read`.
3. Health succeeds with the minimum ServiceNow ACLs intended for the integration account.
4. A valid name search returns at most the configured page limit and exposes only the four governed fields.
5. Query characters outside the allow-list are rejected before egress.
6. Removing Table API/CMDB read rights produces the stable InfraNexum authentication/provider error without leaking the ServiceNow response body.
7. A 429 or provider outage is translated to a stable service-unavailable contract and remains observable through Integration metrics/audit.
8. The Web browser contains no ServiceNow authorization header or bearer credential.
