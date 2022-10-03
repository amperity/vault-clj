#!/bin/bash
# Prerequisite: start the local Vault server with `../dev/server`
# Steps cribbed and adapted from a mixture of:
# - https://learn.hashicorp.com/tutorials/vault/openldap
# - https://www.vaultproject.io/docs/auth/ldap#configuration

ldapadd -cxD "cn=admin,dc=test,dc=com" -f sample-organization.ldif -w ldap-admin

export OPENLDAP_URL=127.0.0.1:389

source ../env.sh

vault auth enable ldap

vault write auth/ldap/config \
    url="ldap://$OPENLDAP_URL" \
    userdn="ou=users,dc=test,dc=com" \
    groupdn="ou=groups,dc=test,dc=com" \
    binddn="cn=admin,dc=test,dc=com" \
    bindpass="ldap-admin"
