/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.NotificationMessage
import org.moqui.context.NotificationMessageListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

@CompileStatic
class NotificationWebSocketListener implements NotificationMessageListener {
    private final static Logger logger = LoggerFactory.getLogger(NotificationWebSocketListener.class)

    private ExecutionContextFactory ecf = null
    private ConcurrentHashMap<String, ConcurrentHashMap<String, NotificationEndpoint>> endpointsByUserTenant = new ConcurrentHashMap<>()

    void registerEndpoint(NotificationEndpoint endpoint) {
        String userTenant = endpoint.userId
        if (userTenant == null) return
        userTenant = userTenant.concat(endpoint.tenantId)
        String sessionId = endpoint.session.id
        ConcurrentHashMap<String, NotificationEndpoint> registeredEndPoints = endpointsByUserTenant.get(userTenant)
        if (registeredEndPoints == null) {
            registeredEndPoints = new ConcurrentHashMap<>()
            ConcurrentHashMap<String, NotificationEndpoint> existing = endpointsByUserTenant.putIfAbsent(userTenant, registeredEndPoints)
            if (existing != null) registeredEndPoints = existing
        }
        NotificationEndpoint existing = registeredEndPoints.putIfAbsent(sessionId, endpoint)
        if (existing != null) logger.warn("Found existing NotificationEndpoint for user ${endpoint.userId} (${existing.username}) in tenant ${endpoint.tenantId}, session ${sessionId}; not registering additional endpoint")
    }
    void deregisterEndpoint(NotificationEndpoint endpoint) {
        String userTenant = endpoint.userId
        if (userTenant == null) return
        userTenant = userTenant.concat(endpoint.tenantId)
        String sessionId = endpoint.session.id
        ConcurrentHashMap<String, NotificationEndpoint> registeredEndPoints = endpointsByUserTenant.get(userTenant)
        if (registeredEndPoints == null) {
            logger.warn("Tried to deregister endpoing for user ${endpoint.userId} in tenant ${endpoint.tenantId} but no endpoints found")
            return
        }
        registeredEndPoints.remove(sessionId)
        if (registeredEndPoints.size() == 0) endpointsByUserTenant.remove(userTenant, registeredEndPoints)
    }

    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
    }

    @Override
    void destroy() {
        endpointsByUserTenant.clear()
        this.ecf = null
    }

    @Override
    void onMessage(NotificationMessage nm) {
        String messageWrapperJson = nm.getWrappedMessageJson()
        for (String userId in nm.getNotifyUserIds()) {
            ConcurrentHashMap<String, NotificationEndpoint> registeredEndPoints = endpointsByUserTenant.get(userId.concat(nm.tenantId))
            if (registeredEndPoints == null) continue
            for (NotificationEndpoint endpoint in registeredEndPoints.values()) {
                if (endpoint.session != null && endpoint.session.isOpen() &&
                        (endpoint.subscribedTopics.contains("ALL") || endpoint.subscribedTopics.contains(nm.topic))) {
                    endpoint.session.asyncRemote.sendText(messageWrapperJson)
                    nm.markSent(userId)
                }
            }
        }
    }
}
