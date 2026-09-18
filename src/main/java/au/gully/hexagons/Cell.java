package au.gully.hexagons;

/**
 * One cell of a {@link Grid}: its id, its axial coordinates and its centre. A value, not a thing that
 * is stored — the grid works any cell out by arithmetic.
 *
 * @param id  {@code q_r}, the key everything about the cell is held under
 * @param lat the centre, where the upstream is asked and the elevation is read
 */
public record Cell(String id, int q, int r, double lat, double lon) {
}
