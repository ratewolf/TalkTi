import pyatspi
import json
import sys

def get_node_data(node, candidate_id):
    try:
        # Get States (Enabled, Visible)
        state_set = node.getState()
        enabled = state_set.contains(pyatspi.STATE_ENABLED)
        # Check if showing and visible
        visible = state_set.contains(pyatspi.STATE_VISIBLE) and state_set.contains(pyatspi.STATE_SHOWING)
        
        if not visible:
            return None

        # Get coordinates
        try:
            bbox = node.get_extents(pyatspi.DESKTOP_COORDS)
        except:
            return None
            
        # Coordinates must be valid (filter out -1, -1 and non-positive sizes)
        if bbox.x < 0 or bbox.y < 0 or bbox.width <= 0 or bbox.height <= 0:
            return None

        name = node.name if node.name else ""
        description = node.description if node.description else ""
        role = node.getRoleName()
        
        # Check if clickable
        clickable = False
        if hasattr(node, 'queryAction'):
            try:
                action = node.queryAction()
                clickable = action.nActions > 0
            except:
                pass

        return {
            "candidateId": f"candidate_{candidate_id}",
            "text": name,
            "contentDescription": description,
            "id": "no_id",
            "className": role, # Map Linux role name to className to match Android format
            "bounds": {
                "left": int(bbox.x),
                "top": int(bbox.y),
                "right": int(bbox.x + bbox.width),
                "bottom": int(bbox.y + bbox.height)
            },
            "clickable": clickable,
            "enabled": enabled,
            "visibleToUser": visible
        }
    except Exception:
        return None

def traverse(node, results, counter):
    if not node:
        return
        
    data = get_node_data(node, counter[0])
    if data:
        # Only add meaningful elements (has text, description or is clickable)
        if data['text'] or data['contentDescription'] or data['clickable']:
            results.append(data)
            counter[0] += 1
    
    try:
        for i in range(node.childCount):
            traverse(node.getChildAtIndex(i), results, counter)
    except:
        pass

def main():
    try:
        registry = pyatspi.Registry
        desktop = registry.getDesktop(0)
        
        results = []
        counter = [0]
        for i in range(desktop.childCount):
            app = desktop.getChildAtIndex(i)
            # Skip TalkTi itself
            if app and app.name != "TalkTi":
                traverse(app, results, counter)
                
        print(json.dumps(results, ensure_ascii=False))
    except Exception:
        print("[]")

if __name__ == "__main__":
    main()
