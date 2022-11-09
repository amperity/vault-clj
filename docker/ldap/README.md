# Local Lightweight Directory Access Protocol (LDAP) Setup

This directory contains a simple set of configuration and scripts to allow running an OpenLDAP
server alongside a local Vault server. It is most directly useful for testing the LDAP auth methods
in `vault.auth.ldap`.

## Prerequisites

1. [Install Docker](https://docs.docker.com/get-docker/)

## Running the OpenLDAP and Vault Servers

1. Start a local vault server:
   ```bash
   ../../dev/server
   ```

1. Start the OpenLDAP docker container:
   ```bash
   cd ../
   docker compose up -d openldap
   ```

1. Setup the LDAP and Vault configuration:
   ```bash
   cd ldap
   ./setup.sh
   ```

Now you can login using the Alice user either via the Vault CLI or REPL.

### Vault CLI

```bash
$ vault login -method=ldap username=alice password=hunter2
Success! You are now authenticated. The token information displayed below
is already stored in the token helper. You do NOT need to run "vault login"
again. Future Vault requests will automatically use this token.

Key                    Value
---                    -----
token                  <token>
token_accessor         N5a2eNiNu7QkbGTslMeYe5mp
token_duration         768h
token_renewable        true
token_policies         ["default"]
identity_policies      []
policies               ["default"]
token_meta_username    alice
```

### Clojure REPL

```clojure
vault.repl=> (init-client)
:init

vault.repl=> (require '[vault.auth.ldap :as auth.ldap])
nil

vault.repl=> (auth.ldap/login client "alice" "hunter2")
{:accessor "wYOZKBSXeZIVBLLyed2xS4ug",
 :client-token "<token>",
 :entity-id "615ee160-5373-d6d8-34b3-bf7b11a8b825",
 :lease-duration 2764800,
 :metadata {:username "alice"},
 :mfa-requirement nil,
 :num-uses 0,
 :orphan true,
 :policies ["default"],
 :renewable true,
 :token-policies ["default"],
 :token-type "service"}
```
