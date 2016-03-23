/*
 * (C) Copyright 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 */

package org.nuxeo.ecm.core.security;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

import static org.nuxeo.ecm.core.api.event.DocumentEventTypes.DOCUMENT_CREATED;

/**
 * Dummy Listener that for checking dc:expired property.
 *
 */
public class CheckExpirationListener implements EventListener {

    private static final Log log = LogFactory.getLog(CheckExpirationListener.class);

    /**
     * Core event notification.
     * <p>
     * Gets core events and checks dc:expired.
     *
     * @param event event fired at core layer
     */
    @Override
    public void handleEvent(Event event) {

        DocumentEventContext docCtx;
        if (event.getContext() instanceof DocumentEventContext) {
            docCtx = (DocumentEventContext) event.getContext();
        } else {
            return;
        }
        String eventId = event.getName();

        if (!eventId.equals(DOCUMENT_CREATED)) {
            return;
        }

        DocumentModel doc = docCtx.getSourceDocument();

        // check for dc:expired
        boolean hasExpiration = doc.getPropertyValue("dc:expired") != null;
        log.debug("Property dc:expired is " + ((hasExpiration) ? "" : "not ") + " specified.");
    }

}
