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

type Variant = "default" | "outline" | "ghost" | "destructive" | "secondary" | "link"
type Size = "default" | "sm" | "lg" | "icon"

const variants: Record<Variant, string> = {
  default: "bg-primary text-primary-foreground shadow hover:bg-primary/90",
  outline: "border border-input bg-transparent shadow-sm hover:bg-accent hover:text-accent-foreground",
  ghost: "hover:bg-accent hover:text-accent-foreground",
  destructive: "bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90",
  secondary: "bg-secondary text-secondary-foreground shadow-sm hover:bg-secondary/80",
  link: "text-primary underline-offset-4 hover:underline",
}

const sizes: Record<Size, string> = {
  default: "h-9 px-4 py-2",
  sm: "h-8 rounded-md px-3 text-xs",
  lg: "h-10 rounded-md px-8",
  icon: "h-9 w-9",
}

export interface ButtonProps extends React.ComponentProps<"button"> {
  variant?: Variant
  size?: Size
  asChild?: boolean
}

function Button({ className, variant = "default", size = "default", asChild = false, type, ...props }: ButtonProps) {
  const Comp: any = asChild ? Slot : "button"
  return (
    <Comp
      type={asChild ? undefined : (type ?? "button")}
      className={cn(
        "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0",
        variants[variant],
        sizes[size],
        className
      )}
      {...props}
    />
  )
}

export { Button }
