/*
 * Copyright (C) 2003-2016 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.exoplatform.onlyoffice.webui;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.exoplatform.portal.webui.portal.UIPortalComponent;
import org.exoplatform.web.application.Application;
import org.exoplatform.webui.application.WebuiRequestContext;
import org.exoplatform.webui.core.UIApplication;
import org.exoplatform.webui.core.UIContainer;

/**
 * This listener should hide navigation toolbar in the Platform page. The code
 * is similar to the one in Outlook add-in.<br>
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: OnlyofficeEditorLifecycle.java 00000 Mar 22, 2019 pnedonosko $
 */
public class OnlyofficeEditorLifecycle extends AbstractOnlyofficeLifecycle {

  /** The Constant EMPTY_PERMISSIONS. */
  public static final String[] EMPTY_PERMISSIONS = new String[0];

  interface RenderedState {
    void restore();
  }

  private final Set<RenderedState> states = ConcurrentHashMap.newKeySet();

  /**
   * Instantiates a new Onlyoffice editor lifecycle.
   */
  public OnlyofficeEditorLifecycle() {
    // Nothing
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onStartRequest(Application app, WebuiRequestContext context) throws Exception {
    super.onStartRequest(app, context);

    // We want hide left sidebar and toolbar, to left all the space for editor
    UIApplication uiApp = context.getUIApplication();
    final UIContainer toolbar = uiApp.findComponentById("UIToolbarContainer");
    if (toolbar != null) {
      toolbar.setRendered(false);
      states.add(() -> toolbar.setRendered(true));
    }
    final UIContainer leftnav = uiApp.findComponentById("LeftNavigation");
    if (leftnav != null) {
      leftnav.setRendered(false);
      if (UIPortalComponent.class.isAssignableFrom(leftnav.getClass())) {
        // It's case of group.xml layout with UITableColumnContainer.gtmpl
        // which render navigation taking in account access permissions only
        // (w/o rendered state of children)
        UIPortalComponent portalComp = UIPortalComponent.class.cast(leftnav);
        String[] origPermissions = portalComp.getAccessPermissions();
        portalComp.setAccessPermissions(EMPTY_PERMISSIONS);
        states.add(() -> {
          leftnav.setRendered(true);
          portalComp.setAccessPermissions(origPermissions);
        });
      } else {
        states.add(() -> leftnav.setRendered(true));
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onEndRequest(Application app, WebuiRequestContext context) throws Exception {
    // Restore states bars for next requests
    restore();
    super.onEndRequest(app, context);
  }

  /**
   * Restore the state.
   */
  void restore() {
    for (Iterator<RenderedState> siter = states.iterator(); siter.hasNext();) {
      try {
        siter.next().restore();
      } finally {
        siter.remove();
      }
    }
  }
}
