/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011, 2012, 2013, 2014 Zimbra, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.nginx;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Config;
import com.zimbra.cs.account.ldap.LdapProv;
import com.zimbra.cs.ldap.ILdapContext;
import com.zimbra.cs.ldap.LdapClient;
import com.zimbra.cs.ldap.LdapConstants;
import com.zimbra.cs.ldap.LdapException;
import com.zimbra.cs.ldap.LdapUsage;
import com.zimbra.cs.ldap.ZAttributes;
import com.zimbra.cs.ldap.ZLdapContext;
import com.zimbra.cs.ldap.ZLdapFilter;
import com.zimbra.cs.ldap.ZLdapFilterFactory;
import com.zimbra.cs.ldap.ZSearchControls;
import com.zimbra.cs.ldap.ZSearchResultEntry;
import com.zimbra.cs.ldap.ZSearchResultEnumeration;
import com.zimbra.cs.ldap.ZSearchScope;
import com.zimbra.cs.ldap.ZLdapFilterFactory.FilterId;
import com.zimbra.cs.nginx.NginxLookupExtension.EntryNotFoundException;
import com.zimbra.cs.nginx.NginxLookupExtension.NginxLookupException;

public class NginxLookupLdapHelper extends AbstractNginxLookupLdapHelper {

    NginxLookupLdapHelper(LdapProv prov) {
        super(prov);
    }

    @Override
    ILdapContext getLdapContext() throws ServiceException {
        return LdapClient.getContext(LdapUsage.NGINX_LOOKUP);
    }

    @Override
    void closeLdapContext(ILdapContext ldapContext) {
        ZLdapContext zlc = LdapClient.toZLdapContext(prov, ldapContext);
        LdapClient.closeContext(zlc);
    }

    @Override
    Map<String, Object> searchDir(ILdapContext ldapContext, String[] returnAttrs,
            Config config, ZLdapFilter filter, String searchBaseConfigAttr)
    throws NginxLookupException {

        ZLdapContext zlc = LdapClient.toZLdapContext(prov, ldapContext);

        Map<String, Object> attrs = null;

        String base  = config.getAttr(searchBaseConfigAttr);
        if (base == null) {
            base = LdapConstants.DN_ROOT_DSE;
        }

        ZSearchControls searchControls = ZSearchControls.createSearchControls(
                ZSearchScope.SEARCH_SCOPE_SUBTREE, 1, returnAttrs);

        ZSearchResultEnumeration ne = null;
        try {
            try {
                ne = zlc.searchDir(base, filter, searchControls);
                if (!ne.hasMore()) {
                    throw new NginxLookupException("query returned empty result: " + filter.toFilterString());
                }
                ZSearchResultEntry sr = ne.next();
                ZAttributes ldapAttrs = sr.getAttributes();
                attrs = ldapAttrs.getAttrs();
            } finally {
                if (ne != null) {
                    ne.close();
                }
            }
        } catch (ServiceException e) {
            throw new NginxLookupException("unable to search LDAP", e);
        }

        return attrs;
    }

    @Override
    SearchDirResult searchDirectory(ILdapContext ldapContext, String[] returnAttrs,
            Config config, FilterId filterId, String queryTemplate, String searchBase,
            String templateKey, String templateVal, Map<String, Boolean> attrs,
            Set<String> extraAttrs)
    throws NginxLookupException {
        ZLdapContext zlc = LdapClient.toZLdapContext(prov, ldapContext);

        HashMap<String, String> kv = new HashMap<String,String>();
        kv.put(templateKey, ZLdapFilterFactory.getInstance().encodeValue(templateVal));

        String query = config.getAttr(queryTemplate);
        String base  = config.getAttr(searchBase);
        if (query == null)
            throw new NginxLookupException("empty attribute: "+queryTemplate);

        ZimbraLog.nginxlookup.debug("query template attr=" + queryTemplate + ", query template=" + query);
        query = StringUtil.fillTemplate(query, kv);
        ZimbraLog.nginxlookup.debug("query=" + query);

        if (base == null) {
            base = LdapConstants.DN_ROOT_DSE;
        }

        ZSearchControls searchControls = ZSearchControls.createSearchControls(
                ZSearchScope.SEARCH_SCOPE_SUBTREE, 1, returnAttrs);

        SearchDirResult sdr = new SearchDirResult();

        ZSearchResultEnumeration ne = null;
        try {
            try {
                ne = zlc.searchDir(base,
                        ZLdapFilterFactory.getInstance().fromFilterString(filterId, query),
                        searchControls);

                if (!ne.hasMore())
                    throw new EntryNotFoundException("query returned empty result: "+query);
                ZSearchResultEntry sr = ne.next();

                sdr.configuredAttrs = new HashMap<String, String>();
                lookupAttrs(sdr.configuredAttrs, config, sr, attrs);

                sdr.extraAttrs = new HashMap<String, String>();
                if (extraAttrs != null) {
                    ZAttributes ldapAttrs = sr.getAttributes();
                    for (String attr : extraAttrs) {
                        String val = ldapAttrs.getAttrString(attr);
                        if (val != null)
                            sdr.extraAttrs.put(attr, val);
                    }
                }
            } finally {
                if (ne != null) {
                    ne.close();
                }
            }
        } catch (ServiceException e) {
            throw new NginxLookupException("unable to search LDAP", e);
        }

        return sdr;
    }

    private void lookupAttrs(Map<String, String> vals, Config config, ZSearchResultEntry sr, Map<String, Boolean> keys)
    throws NginxLookupException, LdapException {
        for (Map.Entry<String, Boolean> keyEntry : keys.entrySet()) {
            String key = keyEntry.getKey();
            String val = lookupAttr(config, sr, key, keyEntry.getValue());
            if (val != null)
                vals.put(key, val);
        }
    }

    private String lookupAttr(Config config, ZSearchResultEntry sr, String key, Boolean required)
    throws NginxLookupException, LdapException {
        String val = null;
        String attr = config.getAttr(key);
        if (attr == null && required)
            throw new NginxLookupException("missing attr in config: "+key);
        if (attr != null) {
            val = sr.getAttributes().getAttrString(attr);
            if (val == null && required)
                throw new NginxLookupException("missing attr in search result: "+attr);
        }
        return val;
    }
}
