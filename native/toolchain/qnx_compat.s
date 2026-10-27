.section .text
.global __isoc99_sscanf
.type __isoc99_sscanf, %function
__isoc99_sscanf:
    b sscanf
.size __isoc99_sscanf, . - __isoc99_sscanf

.global __isoc99_vsscanf
.type __isoc99_vsscanf, %function
__isoc99_vsscanf:
    b vsscanf
.size __isoc99_vsscanf, . - __isoc99_vsscanf

.section .data
.global _ZNSt3__17codecvtIcc11__mbstate_tE2idE
.type _ZNSt3__17codecvtIcc11__mbstate_tE2idE, %object
.align 3
_ZNSt3__17codecvtIcc11__mbstate_tE2idE:
    .quad 0
    .quad 0
.size _ZNSt3__17codecvtIcc11__mbstate_tE2idE, 16
