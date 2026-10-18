import * as React from "react"
import { cn } from "@/lib/utils"

// Minimal Slot: merges props onto the single child element.
function Slot({
  children,
  ...props
}: React.HTMLAttributes<HTMLElement> & { children: React.ReactNode }) {
  const child = React.Children.only(children) as React.ReactElement<{
    className?: string
    onClick?: (e: React.MouseEvent) => void
  }>
  const { className: childClass, onClick: childClick, ...restChild } = child.props
  const { className: slotClass, onClick: slotClick, ...restSlot } = props as React.HTMLAttributes<HTMLElement> & {
    onClick?: (e: React.MouseEvent) => void
  }
  return React.cloneElement(child, {
    ...restSlot,
    ...restChild,
    className: cn(slotClass, childClass),
    onClick: (e: React.MouseEvent) => {
      childClick?.(e)
      slotClick?.(e)
    },
  } as any)
}

const DialogContext = React.createContext<{ open: boolean; setOpen: (o: boolean) => void }>({
  open: false,
  setOpen: () => {},
})

function Dialog({ open, onOpenChange, children }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: React.ReactNode
}) {
  const previouslyFocusedRef = React.useRef<HTMLElement | null>(null)
  const onOpenChangeRef = React.useRef(onOpenChange)
  React.useEffect(() => { onOpenChangeRef.current = onOpenChange })

  React.useEffect(() => {
    if (!open) return
    previouslyFocusedRef.current = document.activeElement as HTMLElement | null
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault()
        onOpenChangeRef.current(false)
      }
    }
    document.addEventListener("keydown", onKeyDown)
    return () => {
      document.removeEventListener("keydown", onKeyDown)
      try { previouslyFocusedRef.current?.focus?.() } catch {}
    }
  }, [open])

  return (
    <DialogContext.Provider value={{ open, setOpen: onOpenChange }}>
      {open ? children : null}
    </DialogContext.Provider>
  )
}

function DialogTrigger({ className, children, asChild = false, ...props }: React.ComponentProps<"button"> & { asChild?: boolean }) {
  const { open, setOpen } = React.useContext(DialogContext)
  const Comp: any = asChild ? Slot : "button"
  return (
    <Comp
      type={asChild ? undefined : "button"}
      aria-expanded={open}
      className={cn(className)}
      onClick={() => setOpen(true)}
      {...props}
    >
      {children}
    </Comp>
  )
}

function DialogContent({ className, children, ...props }: React.ComponentProps<"div">) {
  const { setOpen } = React.useContext(DialogContext)
  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true">
      <div className="fixed inset-0 bg-black/50 backdrop-blur-sm" />
      <div
        className="fixed inset-0 overflow-y-auto"
        onClick={(e) => { if (e.target === e.currentTarget) setOpen(false) }}
      >
        <div className="flex min-h-full items-center justify-center p-4">
          <div
            className={cn("relative w-full max-w-lg rounded-xl border bg-card p-6 shadow-xl", className)}
            {...props}
          >
            {children}
          </div>
        </div>
      </div>
    </div>
  )
}

function DialogHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div className={cn("flex flex-col space-y-1.5 text-center sm:text-left", className)} {...props} />
}

function DialogTitle({ className, ...props }: React.ComponentProps<"h2">) {
  return <h2 className={cn("text-lg font-semibold leading-none tracking-tight", className)} {...props} />
}

function DialogFooter({ className, ...props }: React.ComponentProps<"div">) {
  return <div className={cn("flex flex-col-reverse sm:flex-row sm:justify-end sm:space-x-2", className)} {...props} />
}

function DialogDescription({ className, ...props }: React.ComponentProps<"p">) {
  return <p className={cn("text-sm text-muted-foreground", className)} {...props} />
}

function DialogClose({ className, children, asChild = false, ...props }: React.ComponentProps<"button"> & { asChild?: boolean }) {
  const { setOpen } = React.useContext(DialogContext)
  const Comp: any = asChild ? Slot : "button"
  return (
    <Comp type={asChild ? undefined : "button"} className={cn(className)} onClick={() => setOpen(false)} {...props}>
      {children}
    </Comp>
  )
}

export {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  DialogClose,
}
