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
package com.redhat.rhn.frontend.events;

import java.util.Date;
import java.util.Set;
import java.util.List;
import com.redhat.rhn.common.messaging.EventMessage;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.dto.EssentialServerDto;

/**
 * Event fired to carry the information necessary to schedule package installations
 * on systems in the SSM. 
 */
public class SsmPackageInstallEvent implements EventMessage {

    private User user;
    private Date earliest;
    private Set<String> packages;
    private List<EssentialServerDto> servers;

    /**
     * Creates a new event to install a set of packages on systems in the SSM.
     *
     * @param userIn     user making the changes; cannot be <code>null</code>
     * @param earliestIn earliest time to perform the installation; can be <code>null</code>
     * @param packagesIn set of package IDs being installed; cannot be <code>null</code>
     * @param serversIn  set of server data to install the packages on;
     *                   cannot be <code>null</code>
     */
    public SsmPackageInstallEvent(User userIn,
                                  Date earliestIn,
                                  Set<String> packagesIn,
                                  List<EssentialServerDto> serversIn) {
        if (userIn == null) {
            throw new IllegalArgumentException("userIn cannot be null");
        }

        if (packagesIn == null) {
            throw new IllegalArgumentException("packagesIn cannot be null");
        }

        if (serversIn == null) {
            throw new IllegalArgumentException("serversIn cannot be null");
        }

        this.user = userIn;
        this.earliest = earliestIn;
        this.packages = packagesIn;
        this.servers = serversIn;
    }

    /**
     * @return will not be <code>null</code>
     */
    public User getUser() {
        return user;
    }

    /**
     * @return may be <code>null</code>
     */
    public Date getEarliest() {
        return earliest;
    }

    /**
     * @return will not be <code>null</code>
     */
    public Set<String> getPackages() {
        return packages;
    }

    /**
     * @return will not be <code>null</code>
     */
    public List<EssentialServerDto> getServers() {
        return servers;
    }

    /** {@inheritDoc} */
    public String toText() {
        return toString();
    }

    /** {@inheritDoc} */
    public String toString() {
        return "SsmPackageInstallEvent[User: " + user.getLogin() + ", Package Count: " +
            packages.size() + ", Server Count: " + servers.size() +  "]";
    }
}
