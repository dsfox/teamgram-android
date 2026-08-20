/* The one definition the arm64 assembly reads from pixman's private header.
 *
 * The assembly is upstream pixman, taken whole so it keeps compiling with
 * clang's integrated assembler (see issue #30); upstream includes its full
 * private header, and everything it actually uses from there is this.
 */
#ifndef PIXMAN_PRIVATE_H
#define PIXMAN_PRIVATE_H

#define BILINEAR_INTERPOLATION_BITS 7

#endif
