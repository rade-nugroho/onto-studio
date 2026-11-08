package org.protege.editor.owl.ui.action;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.Predicate;

/**
 * Shows a short pop-up tour of the main Protégé window.
 */
public class TourGuideAction extends ProtegeOWLAction {

    private static final long serialVersionUID = -5151990838608404551L;

    private static final int CARD_WIDTH = 320;

    private static final class Stop {
        final String title;
        final String body;
        final Predicate<Component> anchor;

        Stop(String title, String body, Predicate<Component> anchor) {
            this.title = title;
            this.body = body;
            this.anchor = anchor;
        }
    }

    @Override
    public void initialise() throws Exception {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Window owner = (Window) SwingUtilities.getWindowAncestor(getOWLWorkspace());
        if (owner == null) {
            return;
        }
        Stop[] stops = buildStops();
        JDialog dialog = new JDialog(owner instanceof Frame ? (Frame) owner : null,
                                      "Protégé tour", false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);

        JLabel text = new JLabel();
        text.setVerticalAlignment(SwingConstants.TOP);
        text.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JButton back = new JButton("Back");
        JButton next = new JButton("Next");
        JButton skip = new JButton("Skip tour");
        skip.addActionListener(ev -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 8, 10, 10));
        buttons.add(back);
        buttons.add(skip);
        buttons.add(next);

        JPanel content = new JPanel(new BorderLayout());
        content.add(text, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);

        final int[] index = {0};
        next.addActionListener(ev -> {
            if (index[0] >= stops.length - 1) {
                dialog.dispose();
            } else {
                showStop(dialog, text, back, next, stops, ++index[0]);
            }
        });
        back.addActionListener(ev -> showStop(dialog, text, back, next, stops, --index[0]));

        showStop(dialog, text, back, next, stops, 0);
        dialog.setVisible(true);
    }

    private Stop[] buildStops() {
        return new Stop[]{
                new Stop("Welcome to Protégé",
                         "This tour shows the main parts of the window. "
                                 + "Use Back and Next to move through it, or Skip tour to close it at any time.",
                         null),
                new Stop("Active ontology",
                         "This selector shows the ontology you are editing. "
                                 + "When several ontologies are open, choose another one here to switch the views below.",
                         c -> hasToolTip(c, "The ontology you are editing")),
                new Stop("Entity views",
                         "The tabs list the things in your ontology: classes, properties, individuals, and annotations. "
                                 + "Click a tab to browse or edit that kind of entity.",
                         JTabbedPane.class::isInstance),
                new Stop("Search",
                         "The magnifier searches for a class, property, or individual by name.",
                         c -> hasToolTip(c, "Search for a class, property, or individual")),
                new Stop("Reasoner",
                         "Use the reasoner button in the toolbar to start or stop reasoning. "
                                 + "Once it is running, inferred classes and relationships appear in the views.",
                         null),
                new Stop("Tour complete",
                         "That is the tour. Open the Help menu at any time for documentation and plugin links, "
                                 + "or restart this tour from Help > Start guided tour.",
                         null)
        };
    }

    private void showStop(JDialog dialog, JLabel text, JButton back, JButton next,
                          Stop[] stops, int i) {
        int real = Math.max(0, Math.min(i, stops.length - 1));
        Stop stop = stops[real];
        text.setText("<html><div style='width:" + CARD_WIDTH + "px'>"
                             + "<b>" + stop.title + "</b>"
                             + "<div style='margin-top:6px'>" + stop.body + "</div>"
                             + "<div style='margin-top:8px;color:#666666'>Step " + (real + 1)
                             + " of " + stops.length + "</div></div></html>");
        back.setEnabled(real > 0);
        next.setText(real == stops.length - 1 ? "Finish" : "Next");
        dialog.pack();
        positionNear(dialog, anchorFor(stop));
        dialog.toFront();
    }

    private Component anchorFor(Stop stop) {
        if (stop.anchor == null) {
            return null;
        }
        Window owner = SwingUtilities.getWindowAncestor(getOWLWorkspace());
        if (owner instanceof Container) {
            return findComponent((Container) owner, stop.anchor);
        }
        return null;
    }

    private void positionNear(JDialog dialog, Component anchor) {
        Window owner = dialog.getOwner();
        if (owner == null) {
            dialog.setLocationRelativeTo(null);
            return;
        }
        Rectangle ownerBounds = owner.getBounds();
        int x, y;
        if (anchor != null && anchor.isShowing()) {
            Point anchorLoc = anchor.getLocationOnScreen();
            Point ownerLoc = owner.getLocationOnScreen();
            x = anchorLoc.x - ownerLoc.x;
            y = anchorLoc.y - ownerLoc.y + anchor.getHeight() + 8;
            if (y + dialog.getHeight() > ownerBounds.height) {
                y = anchorLoc.y - ownerLoc.y - dialog.getHeight() - 8;
            }
        } else {
            x = (ownerBounds.width - dialog.getWidth()) / 2;
            y = (ownerBounds.height - dialog.getHeight()) / 2;
        }
        x = Math.max(0, Math.min(x, Math.max(0, ownerBounds.width - dialog.getWidth())));
        y = Math.max(0, Math.min(y, Math.max(0, ownerBounds.height - dialog.getHeight())));
        dialog.setLocation(owner.getLocation().x + x, owner.getLocation().y + y);
    }

    private static boolean hasToolTip(Component c, String prefix) {
        if (!(c instanceof JComponent)) {
            return false;
        }
        String tip = ((JComponent) c).getToolTipText();
        return tip != null && tip.startsWith(prefix);
    }

    private static Component findComponent(Container root, Predicate<Component> match) {
        for (Component c : root.getComponents()) {
            if (match.test(c)) {
                return c;
            }
            if (c instanceof Container) {
                Component found = findComponent((Container) c, match);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
