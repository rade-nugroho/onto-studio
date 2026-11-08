package org.protege.editor.owl.ui.util;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 8 Aug 16
 */
public class NothingSelectedPanel extends JPanel {

    public static final String NOTHING_SELECTED = "Nothing selected";

    public static final String SELECT_SOMETHING = "Select a class, property, or individual.";

    private static final Color BORDER_COLOR = new Color(220, 220, 220);

    private static final Color TEXT_COLOR = new Color(120, 120, 120);

    private static final int BORDER_MARGIN = 20;

    private static final int BORDER_THICKNESS = 8;

    private static final Font FONT = new Font("Sans-serif", Font.PLAIN, 40);

    public NothingSelectedPanel() {
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Stroke currentStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(BORDER_THICKNESS));
        g2.setColor(BORDER_COLOR);
        int borderWidth = getWidth() - 2 * BORDER_MARGIN;
        int borderHeight = getHeight() - 2 * BORDER_MARGIN;
        g2.drawRoundRect(BORDER_MARGIN, BORDER_MARGIN, borderWidth, borderHeight, BORDER_MARGIN, BORDER_MARGIN);
        g2.setStroke(currentStroke);
        g2.setColor(TEXT_COLOR);
        Font hintFont = FONT.deriveFont(16f);
        FontMetrics titleMetrics = g2.getFontMetrics(FONT);
        FontMetrics hintMetrics = g2.getFontMetrics(hintFont);
        int gap = 10;
        int blockHeight = titleMetrics.getHeight() + gap + hintMetrics.getHeight();
        int top = Math.max(BORDER_MARGIN, (getHeight() - blockHeight) / 2);
        int titleBaseline = top + titleMetrics.getAscent();
        int hintBaseline = top + titleMetrics.getHeight() + gap + hintMetrics.getAscent();
        drawCentered(g2, NOTHING_SELECTED, FONT, titleBaseline);
        drawCentered(g2, SELECT_SOMETHING, hintFont, hintBaseline);
    }

    private void drawCentered(Graphics2D g2, String text, Font font, int baseline) {
        g2.setFont(font);
        Rectangle2D bounds = g2.getFontMetrics().getStringBounds(text, g2);
        int x = (int) (getWidth() - bounds.getWidth()) / 2;
        g2.drawString(text, x, baseline);
    }
}
