/*
 * Copyright 2015-2018 _floragunn_ GmbH
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.configuration;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import com.google.common.collect.ImmutableMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.common.settings.Settings;
import org.opensearch.security.support.ConfigConstants;
import org.opensearch.security.support.WildcardMatcher;
import org.opensearch.security.user.User;

public class AdminDNs {

    protected final Logger log = LogManager.getLogger(AdminDNs.class);
    private final Set<LdapName> adminDn = new HashSet<LdapName>();
    private final Set<String> adminUsernames = new HashSet<String>();
    private final Map<LdapName, WildcardMatcher> allowedDnsImpersonations;
    private final Map<String, WildcardMatcher> allowedRestImpersonations;
    private boolean injectUserEnabled;
    private boolean injectAdminUserEnabled;

    public AdminDNs(final Settings settings) {

        this.injectUserEnabled = settings.getAsBoolean(ConfigConstants.SECURITY_UNSUPPORTED_INJECT_USER_ENABLED, false);
        this.injectAdminUserEnabled = settings.getAsBoolean(ConfigConstants.SECURITY_UNSUPPORTED_INJECT_ADMIN_USER_ENABLED, false);

        final List<String> adminDnsA = settings.getAsList(ConfigConstants.SECURITY_AUTHCZ_ADMIN_DN, Collections.emptyList());

        for (String dn : adminDnsA) {
            try {
                log.debug("{} is registered as an admin dn", dn);
                adminDn.add(new LdapName(dn));
            } catch (final InvalidNameException e) {
                // make sure to log correctly depending on user injection settings
                if (injectUserEnabled && injectAdminUserEnabled) {
                    if (log.isDebugEnabled()) {
                        log.debug("Admin DN not an LDAP name, but admin user injection enabled. Will add {} to admin usernames", dn);
                    }
                    adminUsernames.add(dn);
                } else {
                    log.error("Unable to parse admin dn {}", dn, e);
                }
            }
        }

        log.debug("Loaded {} admin DN's {}", adminDn.size(), adminDn);

        final Settings impersonationDns = settings.getByPrefix(ConfigConstants.SECURITY_AUTHCZ_IMPERSONATION_DN + ".");

        allowedDnsImpersonations = impersonationDns.keySet()
            .stream()
            .map(this::toLdapName)
            .filter(Objects::nonNull)
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(),
                    ldapName -> WildcardMatcher.from(settings.getAsList(ConfigConstants.SECURITY_AUTHCZ_IMPERSONATION_DN + "." + ldapName))
                )
            );

        log.debug("Loaded {} impersonation DN's {}", allowedDnsImpersonations.size(), allowedDnsImpersonations);

        final Settings impersonationUsersRest = settings.getByPrefix(ConfigConstants.SECURITY_AUTHCZ_REST_IMPERSONATION_USERS + ".");

        allowedRestImpersonations = impersonationUsersRest.keySet()
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(),
                    user -> WildcardMatcher.from(settings.getAsList(ConfigConstants.SECURITY_AUTHCZ_REST_IMPERSONATION_USERS + "." + user))
                )
            );

        log.debug("Loaded {} impersonation users for REST {}", allowedRestImpersonations.size(), allowedRestImpersonations);
    }

    private LdapName toLdapName(String dn) {
        try {
            return new LdapName(dn);
        } catch (final InvalidNameException e) {
            log.error("Unable to parse allowedImpersonations dn {}", dn, e);
        }
        return null;
    }

    public boolean isAdmin(User user) {
        if (isAdminDN(user.getName())) {
            return true;
        }

        // ThreadContext injected user, may be admin user, only if both flags are enabled and user is injected
        if (injectUserEnabled && injectAdminUserEnabled && user.isInjected() && adminUsernames.contains(user.getName())) {
            return true;
        }
        return false;
    }

    public boolean isAdminDN(String dn) {

        if (dn == null) return false;

        try {
            return isAdminDN(new LdapName(dn));
        } catch (InvalidNameException e) {
            return false;
        }
    }

    private boolean isAdminDN(LdapName dn) {
        if (dn == null) return false;

        boolean isAdmin = adminDn.contains(dn);

        if (log.isTraceEnabled()) {
            log.trace("Is principal {} an admin cert? {}", dn.toString(), isAdmin);
        }

        return isAdmin;
    }

    public boolean isRestImpersonationAllowed(final String originalUser, final String impersonated) {
        return (originalUser != null)
            ? allowedRestImpersonations.getOrDefault(originalUser, WildcardMatcher.NONE).test(impersonated)
            : false;
    }
}
