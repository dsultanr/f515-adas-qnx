.section .note, "a", %note
.p2align 2
.4byte 4            /* namesz */
.4byte 8            /* descsz */
.4byte 3            /* type: QNT_STACK = 3 */
.asciz "QNX"        /* name */
.byte 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00

.section .text
.global _start
.type _start, %function

_start:
    mov     x4, x0              /* dynamic linker cleanup callback */
    ldr     w0, [sp]            /* argc */
    add     x1, sp, #8          /* argv */
    add     x2, x1, x0, lsl #3
    add     x2, x2, #8          /* envp */
    mov     x3, x2              /* scan for auxv */
1:
    ldr     x16, [x3], #8
    cmp     x16, #0
    b.ne    1b                  /* x3 now points to auxv */

    mov     x19, x0             /* save argc */
    mov     x20, x1             /* save argv */
    mov     x21, x2             /* save envp */

    bl      _init_libc

    adrp    x0, __preinit_array_start
    add     x0, x0, :lo12:__preinit_array_start
    adrp    x1, __preinit_array_end
    add     x1, x1, :lo12:__preinit_array_end
    bl      _preinit_array

    adrp    x0, __fini_array_start
    add     x0, x0, :lo12:__fini_array_start
    adrp    x1, __fini_array_end
    add     x1, x1, :lo12:__fini_array_end
    bl      _fini_array

    adrp    x0, __init_array_start
    add     x0, x0, :lo12:__init_array_start
    adrp    x1, __init_array_end
    add     x1, x1, :lo12:__init_array_end
    bl      _init_array

    bl      __get_errno_ptr
    str     wzr, [x0]

    mov     w0, w19
    mov     x1, x20
    mov     x2, x21
    bl      main
    bl      exit
2:  b       2b
