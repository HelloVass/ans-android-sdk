package com.analysys.visual.viewcrawler;

import android.view.accessibility.AccessibilityEvent;

import com.analysys.utils.InternalAgent;
import com.analysys.visual.utils.EGJSONUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class EditProtocol {

    private static final Class<?>[] NO_PARAMS = new Class[0];
    private static final List<Pathfinder.PathElement> NEVER_MATCH_PATH =
            Collections.<Pathfinder.PathElement>emptyList();
    private final ResourceIds mResourceIds;

    public EditProtocol(ResourceIds resourceIds) {
        mResourceIds = resourceIds;
    }

    public BaseViewVisitor readEventBinding(JSONObject source,
                                            BaseViewVisitor.OnEventListener listener)
            throws BadInstructionsException {
        try {
            final String eventID = source.getString("event_id");
            final String eventType = source.getString("event_type");
            String matchType = null;
            if (source.has("match_text")) {
                matchType = source.getString("match_text");
                if (InternalAgent.isEmpty(matchType)) {
                    matchType = null;
                }
            }
            final JSONArray pathDesc = source.getJSONArray("path");
            final List<Pathfinder.PathElement> path = readPath(pathDesc, mResourceIds);

            if (path.size() == 0) {
                throw new InapplicableInstructionsException("event '" + eventID + "' will not " +
                        "be bound to any element in the UI.");
            }

            if ("click".equals(eventType)) {
                return new BaseViewVisitor.AddAccessibilityEventVisitor(
                        path,
                        AccessibilityEvent.TYPE_VIEW_CLICKED,
                        eventID,
                        matchType,
                        listener
                );
            } else if ("selected".equals(eventType)) {
                return new BaseViewVisitor.AddAccessibilityEventVisitor(
                        path,
                        AccessibilityEvent.TYPE_VIEW_SELECTED,
                        eventID,
                        matchType,
                        listener
                );
            } else if ("text_changed".equals(eventType)) {
                return new BaseViewVisitor.AddTextChangeListener(path, eventID, matchType,
                        listener);
            } else if ("detected".equals(eventType)) {
                return new BaseViewVisitor.ViewDetectorVisitor(path, eventID, matchType, listener);
            } else {
                throw new BadInstructionsException("can't track event type \"" +
                        eventType + "\"");
            }
        } catch (final JSONException e) {
            throw new BadInstructionsException("Can't interpret instructions due to " +
                    "JSONException", e);
        }
    }

    public ViewSnapshot readSnapshotConfig(JSONObject source) throws BadInstructionsException {
        final List<PropertyDescription> properties = new ArrayList<PropertyDescription>();

        try {
            final JSONObject config = source.getJSONObject("config");
            final JSONArray classes = config.getJSONArray("classes");
            for (int classIx = 0; classIx < classes.length(); classIx++) {
                final JSONObject classDesc = classes.getJSONObject(classIx);
                final String targetClassName = classDesc.getString("name");
                final Class<?> targetClass = Class.forName(targetClassName);

                final JSONArray propertyDescs = classDesc.getJSONArray("properties");
                for (int i = 0; i < propertyDescs.length(); i++) {
                    final JSONObject propertyDesc = propertyDescs.getJSONObject(i);
                    final PropertyDescription desc = readPropertyDescription(targetClass,
                            propertyDesc);
                    properties.add(desc);
                }
            }

            return new ViewSnapshot(properties, mResourceIds);
        } catch (JSONException e) {
            throw new BadInstructionsException("Can't read snapshot configuration", e);
        } catch (final ClassNotFoundException e) {
            throw new BadInstructionsException("Can't resolve types for snapshot configuration", e);
        }
    }

    // Package access FOR TESTING ONLY
    /* package */ List<Pathfinder.PathElement> readPath(JSONArray pathDesc, ResourceIds
            idNameToId) throws JSONException {
        final List<Pathfinder.PathElement> path = new ArrayList<Pathfinder.PathElement>();

        for (int i = 0; i < pathDesc.length(); i++) {
            final JSONObject targetView = pathDesc.getJSONObject(i);

            final String prefixCode = EGJSONUtils.optionalStringKey(targetView, "prefix");
            final String targetViewClass = EGJSONUtils.optionalStringKey(targetView, "view_class");
            final int targetIndex = targetView.optInt("index", -1);
            final String targetDescription = EGJSONUtils.optionalStringKey(targetView,
                    "contentDescription");
            final int targetExplicitId = targetView.optInt("id", -1);
            final String targetIdName = EGJSONUtils.optionalStringKey(targetView, "mp_id_name");
            final String targetTag = EGJSONUtils.optionalStringKey(targetView, "tag");

            final int prefix;
            if ("shortest".equals(prefixCode)) {
                prefix = Pathfinder.PathElement.SHORTEST_PREFIX;
            } else if (null == prefixCode) {
                prefix = Pathfinder.PathElement.ZERO_LENGTH_PREFIX;
            } else {
                return NEVER_MATCH_PATH;
            }

            final int targetId;

            final Integer targetIdOrNull = reconcileIds(targetExplicitId, targetIdName, idNameToId);
            if (null == targetIdOrNull) {
                return NEVER_MATCH_PATH;
            } else {
                targetId = targetIdOrNull.intValue();
            }

            path.add(new Pathfinder.PathElement(prefix, targetViewClass, targetIndex, targetId,
                    targetDescription, targetTag));
        }

        return path;
    }

    // May return null (and log a warning) if arguments cannot be reconciled
    private Integer reconcileIds(int explicitId, String idName, ResourceIds idNameToId) {
        final int idFromName;
        if (null != idName) {
            if (idNameToId.knownIdName(idName)) {
                idFromName = idNameToId.idFromName(idName);
            } else {
                InternalAgent.w(
                        "Path element contains an id name not known to the system. No views will " +
                                "be matched.\n" +
                                "Make sure that you're not stripping your packages R class out " +
                                "with proguard.\n" +
                                "id name was \"" + idName + "\""
                );
                return null;
            }
        } else {
            idFromName = -1;
        }

        if (-1 != idFromName && -1 != explicitId && idFromName != explicitId) {
            InternalAgent.w("Path contains both a named and an explicit id, and they don't match. No" +
                    " views will be matched.");
            return null;
        }

        if (-1 != idFromName) {
            return idFromName;
        }

        return explicitId;
    }

    private PropertyDescription readPropertyDescription(Class<?> targetClass, JSONObject
            propertyDesc)
            throws BadInstructionsException {
        try {
            final String propName = propertyDesc.getString("name");

            Caller accessor = null;
            if (propertyDesc.has("get")) {
                final JSONObject accessorConfig = propertyDesc.getJSONObject("get");
                final String accessorName = accessorConfig.getString("selector");
                final String accessorResultTypeName = accessorConfig.getJSONObject("result")
                        .getString("type");
                final Class<?> accessorResultType = Class.forName(accessorResultTypeName);
                accessor = new Caller(targetClass, accessorName, NO_PARAMS, accessorResultType);
            }

            final String mutatorName;
            if (propertyDesc.has("set")) {
                final JSONObject mutatorConfig = propertyDesc.getJSONObject("set");
                mutatorName = mutatorConfig.getString("selector");
            } else {
                mutatorName = null;
            }

            return new PropertyDescription(propName, targetClass, accessor, mutatorName);
        } catch (final NoSuchMethodException e) {
            throw new BadInstructionsException("Can't create property reader", e);
        } catch (final JSONException e) {
            throw new BadInstructionsException("Can't read property JSON", e);
        } catch (final ClassNotFoundException e) {
            throw new BadInstructionsException("Can't read property JSON, relevant arg/return " +
                    "class not found", e);
        }
    }

    public static class BadInstructionsException extends Exception {
        private static final long serialVersionUID = -4062004792184145311L;

        public BadInstructionsException(String message) {
            super(message);
        }

        public BadInstructionsException(String message, Throwable e) {
            super(message, e);
        }
    }

    public static class InapplicableInstructionsException extends BadInstructionsException {
        private static final long serialVersionUID = 3977056710817909104L;

        public InapplicableInstructionsException(String message) {
            super(message);
        }
    }
}
