/**
 * Copyright (c) 2009 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */

package com.redhat.rhn.domain.user.legacy;

import com.redhat.rhn.common.conf.Config;
import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.util.MD5Crypt;
import com.redhat.rhn.domain.BaseDomainHelper;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.org.usergroup.UserGroup;
import com.redhat.rhn.domain.role.Role;
import com.redhat.rhn.domain.role.RoleFactory;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.user.Address;
import com.redhat.rhn.domain.user.EnterpriseUser;
import com.redhat.rhn.domain.user.RhnTimeZone;
import com.redhat.rhn.domain.user.StateChange;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.domain.user.UserFactory;
import com.redhat.rhn.manager.user.UserManager;

import net.sf.jpam.Pam;
import net.sf.jpam.PamReturnValue;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class UserImpl that reflects the DB representation of web_contact
 * and ancillary tables.
 * DB table: web_contact
 * @version $Rev: 75755 $
 */
public class LegacyRhnUserImpl extends BaseDomainHelper implements User {


    private static final Logger LOG = Logger.getLogger(LegacyRhnUserImpl.class);

    private Long id;
    private Set usergroups;
    private Org org;
    private Set stateChanges;
    private Set addresses;
    private Set hiddenPanes;
    private Set associatedServerGroups;
    private Set<Server> servers;
    // PersonalInfo sub-object object
    private PersonalInfo personalInfo;


    // Keep track of whether the user used to be an org admin
    private Boolean wasOrgAdmin;

    // Set of com.redhat.rhn.monitoring.notification.Method instances
    private Set notificationMethods;

    /**
     * Create a new empty user
     */
    public LegacyRhnUserImpl() {
        usergroups = new HashSet();
        stateChanges = new TreeSet();
        addresses = new HashSet();
        hiddenPanes = new HashSet();
        associatedServerGroups = new HashSet();
    }

    /**
     * Gets the current value of id
     * @return long the current value
     */
    public Long getId() {
        return this.id;
    }

    /**
     * Sets the value of id to new value
     * @param idIn New value for id
     */
    public void setId(Long idIn) {
        this.id = idIn;
    }

    /**
     * Gets the current value of login
     * @return String the current value
     */
    public String getLogin() {
        return this.getPersonalInfo().getLogin();
    }

    /**
     * Sets the value of login to new value
     * @param loginIn New value for login
     */
    public void setLogin(String loginIn) {
        this.getPersonalInfo().setLogin(loginIn);
    }

    /**
     * Gets the current value of loginUc
     * @return String the current value
     */
    public String getLoginUc() {
        return this.getPersonalInfo().getLoginUc();
    }

    /**
     * Sets the value of loginUc to new value
     * @param loginUcIn New value for loginUc
     */
    public void setLoginUc(String loginUcIn) {
        this.getPersonalInfo().setLoginUc(loginUcIn);
    }

    /**
     * Gets the current value of password
     * @return String the current value
     */
    public String getPassword() {
        return this.getPersonalInfo().getPassword();
    }

    /**
     * Sets the value of password to new value
     * @param passwordIn New value for password
     */
    public void setPassword(String passwordIn) {
        this.getPersonalInfo().setPassword(passwordIn);
    }

    /**
     * Set the usergroup set
     * @param ugIn The new Set of UserGroups to set
     */
    protected void setUsergroups(Set ugIn) {
        usergroups = ugIn;
    }

    /** get the set of usergroups
     * @return Set of UserGroups
     */
    protected Set getUsergroups() {
        return usergroups;
    }

    /** {@inheritDoc} */
    public Set getRoles() {
        Set userRoles = new HashSet();
        for (Iterator i = usergroups.iterator(); i.hasNext();) {
            UserGroup ug = (UserGroup)i.next();
            userRoles.add(ug.getRole());
        }

        if (userRoles.contains(RoleFactory.ORG_ADMIN)) {
            Set orgRoles = org.getRoles();
            Set localImplied = new HashSet();
            localImplied.addAll(UserFactory.IMPLIEDROLES);
            localImplied.retainAll(orgRoles);
            userRoles.addAll(localImplied);
        }
        return Collections.unmodifiableSet(userRoles);
    }

    /** {@inheritDoc} */
    public boolean hasRole(Role label) {
        // We use checkRoleSet to get the correct logic for the
        // implied roles.
        return getRoles().contains(label);
    }

    /** {@inheritDoc} */
    public void addRole(Role label) {
        checkOrgAdmin();
        UserGroup ug = org.getUserGroup(label);
        if (ug != null) {
            usergroups.add(ug);
        } 
        else {
            throw new IllegalArgumentException("Org doesn't have role: " + label);    
        }
    }


    /** {@inheritDoc} */
    public void removeRole(Role label) {
        checkOrgAdmin();
        UserGroup ug = org.getUserGroup(label);
        if (ug != null) {
            usergroups.remove(ug);
        }
    }

    /**
     * Determine if this was org admin.
     * @return Boolean
     */
    public Boolean wasOrgAdmin() {
        return wasOrgAdmin;
    }

    /**
     * Reset the wasOrgAdmin value.
     */
    public void resetWasOrgAdmin() {
        wasOrgAdmin = null;
    }

    private void checkOrgAdmin() {
        if (wasOrgAdmin == null) {
            wasOrgAdmin = Boolean.valueOf(hasRole(RoleFactory.ORG_ADMIN));
        }
    }

    /** {@inheritDoc} */
    public boolean authenticate(String thePassword) {
        String pamAuthService = Config.get().getString(ConfigDefaults.WEB_PAM_AUTH_SERVICE);
        boolean result = false;
        /*
         * If we have a valid pamAuthService and the user uses pam authentication,
         * authenticate via pam, otherwise, use the db.
         */
        if (pamAuthService != null && pamAuthService.trim().length() > 0 &&
                this.getUsePamAuthentication()) {
            Pam pam = new Pam(pamAuthService);
            PamReturnValue ret = pam.authenticate(getLogin(), thePassword);
            result = PamReturnValue.PAM_SUCCESS.equals(ret);
            if (!result) {
                LOG.warn("PAM login for user " + this + " failed with error " + ret);
            }
        }
        else {
            /**
             * If we're using encrypted passwords, check
             * thePassword encrypted, otherwise just do
             * a straight clear-text comparison.
             */
            boolean useEncrPasswds =
                Config.get().getBoolean(ConfigDefaults.WEB_ENCRYPTED_PASSWORDS);
            if (useEncrPasswds) {
                result = MD5Crypt.crypt(thePassword, this.getPassword()).equals(this.getPassword());
            }
            else {
                result = this.getPassword().equals(thePassword);
            }
            if (LOG.isDebugEnabled() && !useEncrPasswds) {
                String encr = useEncrPasswds ? "with" : "without";
                LOG.debug("DB login for user " + this + " " +
                        encr + " encrypted passwords failed");
            }
        }
        if (LOG.isDebugEnabled() && result) {
            LOG.debug("PAM login for user " + this + " succeeded. ");
        }
        return result;
    }

    /**
     * Associates the user with an Org.
     * @param orgIn Org to be associated to this user.
     */
    public void setOrg(Org orgIn) {
        org = orgIn;
    }

    /** {@inheritDoc} */
    public Org getOrg() {
        return org;
    }

    /** {@inheritDoc} */
    public Set getDefaultSystemGroupIds() {
        return UserManager.getDefaultSystemGroupIds(this);
    }

    /** {@inheritDoc} */
    public void setDefaultSystemGroupIds(Set dsg) {
        UserManager.setDefaultSystemGroupIds(this, dsg);
    }

    /**
    * Return the PersonalInfo object
    * @return PersonalInfo object associated with this User
    */
    public PersonalInfo getPersonalInfo() {
        if (personalInfo == null) {
            personalInfo = new PersonalInfo();
        }
        return personalInfo;
    }

    /**
     * Set the PersonalInfo object
     * @param persIn the PersonalInfo object
     */
    public void setPersonalInfo(PersonalInfo persIn) {
        this.personalInfo = persIn;
    }

    /**
     * Get the UserInfo sub object
     * @return UserInfo
     */
    public UserInfo getUserInfo() {
        return this.getPersonalInfo().getUserInfo();
    }


    /**
     * Convenience method to determine whether a user is disabled
     * or not
     * @return Returns true if the user is disabled
     */
    public boolean isDisabled() {
        return UserFactory.isDisabled(this);
    }

    /**
     * {@inheritDoc}
     */
    public void addChange(StateChange change) {
        this.stateChanges.add(change);
    }

    /**
     * {@inheritDoc}
     */
    public Set getStateChanges() {
        return stateChanges;
    }

    /**
     * @param s The stateChanges to set.
     */
    public void setStateChanges(Set s) {
        this.stateChanges = s;
    }

    /*************   UserInfo methods **************/
    /** {@inheritDoc} */
    public int getPageSize() {
        return this.getUserInfo().getPageSize();
    }

    /** {@inheritDoc} */
    public void setPageSize(int pageSizeIn) {
        this.getUserInfo().setPageSize(pageSizeIn);
    }

    /** {@inheritDoc} */
    public boolean getUsePamAuthentication() {
        return this.getUserInfo().getUsePamAuthentication();
    }

    /** {@inheritDoc} */
    public void setUsePamAuthentication(boolean usePamAuthenticationIn) {
        this.getUserInfo().setUsePamAuthentication(usePamAuthenticationIn);
    }

    /** {@inheritDoc} */
    public String getShowSystemGroupList() {
        return this.getUserInfo().getShowSystemGroupList();
    }

    /** {@inheritDoc} */
    public void setShowSystemGroupList(String showSystemGroupListIn) {
        this.getUserInfo().setShowSystemGroupList(showSystemGroupListIn);
    }

    /** {@inheritDoc} */
    public Date getLastLoggedIn() {
        return this.getUserInfo().getLastLoggedIn();
    }

    /** {@inheritDoc} */
    public void setLastLoggedIn(Date lastLoggedInIn) {
        this.getUserInfo().setLastLoggedIn(lastLoggedInIn);
    }
    
    /** {@inheritDoc} */
    public void setPreferredLocale(String locale) {
        this.getUserInfo().setPreferredLocale(locale);
    }
    
    /** {@inheritDoc} */
    public String getPreferredLocale() {
        return this.getUserInfo().getPreferredLocale();
    }

    /********* PersonalInfo Methods **********/

    /**
     * Gets the current value of prefix
     * @return String the current value
     */
    public String getPrefix() {
        return this.personalInfo.getPrefix();
    }

    /**
     * Sets the value of prefix to new value
     * @param prefixIn New value for prefix
     */
    public void setPrefix(String prefixIn) {
        this.personalInfo.setPrefix(prefixIn);
    }

    /**
     * Gets the current value of firstNames
     * @return String the current value
     */
    public String getFirstNames() {
        return this.personalInfo.getFirstNames();
    }

    /**
     * Sets the value of firstNames to new value
     * @param firstNamesIn New value for firstNames
     */
    public void setFirstNames(String firstNamesIn) {
        this.personalInfo.setFirstNames(firstNamesIn);
    }

    /**
     * Gets the current value of lastName
     * @return String the current value
     */
    public String getLastName() {
        return this.personalInfo.getLastName();
    }

    /**
     * Sets the value of lastName to new value
     * @param lastNameIn New value for lastName
     */
    public void setLastName(String lastNameIn) {
        this.personalInfo.setLastName(lastNameIn);
    }

    /**
     * Gets the current value of company
     * @return String the current value
     */
    public String getCompany() {
        return this.personalInfo.getCompany();
    }

    /**
     * Sets the value of company to new value
     * @param companyIn New value for company
     */
    public void setCompany(String companyIn) {
        this.personalInfo.setCompany(companyIn);
    }

    /**
     * Gets the current value of title
     * @return String the current value
     */
    public String getTitle() {
        return this.personalInfo.getTitle();
    }

    /**
     * Sets the value of title to new value
     * @param titleIn New value for title
     */
    public void setTitle(String titleIn) {
        this.personalInfo.setTitle(titleIn);
    }

    /**
     * Gets the current value of phone
     * @return String the current value
     */
    public String getPhone() {
        return getAddress().getPhone();
    }

    /**
     * Sets the value of phone to new value
     * @param phoneIn New value for phone
     */
    public void setPhone(String phoneIn) {
        getAddress().setPhone(phoneIn);
    }

    /**
     * Gets the current value of fax
     * @return String the current value
     */
    public String getFax() {
        return getAddress().getFax();
    }

    /**
     * Sets the value of fax to new value
     * @param faxIn New value for fax
     */
    public void setFax(String faxIn) {
        getAddress().setFax(faxIn);
    }

    /**
     * Gets the current value of email
     * @return String the current value
     */
    public String getEmail() {
        return this.personalInfo.getEmail();
    }

    /**
     * Sets the value of email to new value
     * @param emailIn New value for email
     */
    public void setEmail(String emailIn) {
        this.personalInfo.setEmail(emailIn);
    }


    /** {@inheritDoc} */
    public RhnTimeZone getTimeZone() {
        return this.getUserInfo().getTimeZone();
    }

    /** {@inheritDoc} */
    public void setTimeZone(RhnTimeZone timeZoneIn) {
        this.getUserInfo().setTimeZone(timeZoneIn);
    }

    /**
     * {@inheritDoc}
     */
    public Set getNotificationMethods() {
        return this.notificationMethods;
    }

    /**
     * {@inheritDoc}
     */
    public void setNotificationMethods(Set methodsIn) {
        this.notificationMethods = methodsIn;
    }




    /**
    * Output User to String for debugging
    * @return String output of the User
    */
    public String toString() {
        StringBuffer retval = new StringBuffer();
        retval.append(LocalizationService.getInstance().
                                   getDebugMessage("user"));
        retval.append(" ");
        retval.append(getLogin());
        retval.append(" (id ");
        retval.append(String.valueOf(getId()));
        retval.append(", org_id ");
        retval.append(String.valueOf(getOrg().getId()));
        retval.append(")");
        return retval.toString();
    }

    /**
     * {@inheritDoc}
     */
    public boolean equals(Object other) {
        if (other == null || !(other instanceof User)) {
            return false;
        }
        User otherUser = (User) other;
        return new EqualsBuilder().append(this.getLogin(), otherUser.getLogin())
                                  .append(org, otherUser.getOrg())
                                  .append(id, otherUser.getId())
                                  .isEquals();
    }

    /**
     * {@inheritDoc}
     */
    public int hashCode() {
        return new HashCodeBuilder().append(this.getLogin()).append(org).append(id).toHashCode();
    }

    /**
     * Getter for address1
     * @return Address1
     */
    public String getAddress1() {
        return getAddress().getAddress1();
    }

    /**
     * Setter for address1
     * @param address1In New value for address1
     */
    public void setAddress1(String address1In) {
        getAddress().setAddress1(address1In);
    }

    /**
     * Getter for address2
     * @return Address2
     */
    public String getAddress2() {
        return getAddress().getAddress2();
    }

    /**
     * Setter for address2
     * @param address2In New value for address2
     */
    public void setAddress2(String address2In) {
        getAddress().setAddress2(address2In);
    }

    /**
     * Getter for city
     * @return City
     */
    public String getCity() {
        return getAddress().getCity();
    }

    /**
     * Setter for city
     * @param cityIn New value for city
     */
    public void setCity(String cityIn) {
        getAddress().setCity(cityIn);
    }

    /**
     * Getter for state
     * @return State
     */
    public String getState() {
        return getAddress().getState();
    }

    /**
     * Setter for state
     * @param stateIn New value for state
     */
    public void setState(String stateIn) {
        getAddress().setState(stateIn);
    }

    /**
     * Getter for zip
     * @return Zip
     */
    public String getZip() {
        return getAddress().getZip();
    }

    /**
     * Setter for zip
     * @param zipIn New value for zip
     */
    public void setZip(String zipIn) {
        getAddress().setZip(zipIn);
    }

    /**
     * Getter for country
     * @return Country
     */
    public String getCountry() {
        return getAddress().getCountry();
    }

    /**
     * Setter for country
     * @param countryIn New value for country
     */
    public void setCountry(String countryIn) {
        getAddress().setCountry(countryIn);
    }


    /**
     * Getter for isPoBox
     * @return isPoBox
     */
    public String getIsPoBox() {
        return getAddress().getIsPoBox();
    }

    /**
     * Setter for isPoBox
     * @param isPoBoxIn New value for isPoBox
     */
    public void setIsPoBox(String isPoBoxIn) {
        getAddress().setIsPoBox(isPoBoxIn);
    }

    /**
     * {@inheritDoc}
     */
    public Set getHiddenPanes() {
        return hiddenPanes;
    }

    /**
     * {@inheritDoc}
     */
    public void setHiddenPanes(Set p) {
        hiddenPanes = p;
    }

    protected void setAddress(Address addIn) {
        addresses.clear();
        addresses.add(addIn);
    }

    public Address getAddress() {
        Address baddr = null;
        Address addr = null;
        Address[] addrA = (Address[]) addresses.toArray(new Address[addresses.size()]);
        if (addresses.size() > 0) {
            for (int i = 0; i < addrA.length; i++) {
                if (addrA[i].getType().equals(Address.TYPE_MARKETING)) {
                    addr = addrA[i];
                }
                if (addrA[i].getType().equals("B")) {
                    baddr = addrA[i];
                }
            }
        }
        if (addr == null) {
            addr = UserFactory.createAddress();
            if (baddr != null) {
                addr.setAddress1(baddr.getAddress1());
                addr.setAddress2(baddr.getAddress2());
                addr.setCity(baddr.getCity());
                addr.setCountry(baddr.getCountry());
                addr.setFax(baddr.getFax());
                addr.setIsPoBox(baddr.getIsPoBox());
                addr.setPhone(baddr.getPhone());
                addr.setState(baddr.getState());
                addr.setZip(baddr.getZip());
            }
            addresses.add(addr);
        }
        return addr;
    }

    /**
     * Set the addresses.
     * @param s the set
     */
    protected void setAddresses(Set s) {
        addresses = s;
    }

    /**
     * Get the addresses
     * @return Set of addresses
     */
    protected Set getAddresses() {
        return addresses;
    }
    
    /** {@inheritDoc} */
    public void setEmailNotify(int emailNotifyIn) {
       this.getUserInfo().setEmailNotify(emailNotifyIn);
    }
    
    /** {@inheritDoc} */
    public int getEmailNotify() {
        return this.getUserInfo().getEmailNotify();
    }

    /**
     * 
     * {@inheritDoc}
     */
    public Set getAssociatedServerGroups() {
        return associatedServerGroups;
    }

    /**
     * Sets the associatedServerGroups.. 
     * Meant for use by hibernate only (hence protected) 
     * @param serverGroups the servergroups to set.
     */
    protected void setAssociatedServerGroups(Set serverGroups) {
        associatedServerGroups = serverGroups;
    }

    /** {@inheritDoc} */
    public Set<Server> getServers() {
        return servers;
    }

    private void setServers(Set<Server> serversIn) {
        this.servers = serversIn;
    }

    /** {@inheritDoc} */
    public void addServer(Server server) {
        servers.add(server);
    }

    /** {@inheritDoc} */
    public void removeServer(Server server) {
        servers.remove(server);
    }
    
    public List<Org> getUserOrgs() {
        return UserFactory.getInstance().listOrgsForUser(this);
    }

    
}


