/** Agency name (VITE_AGENCY_NAME) and logo (VITE_AGENCY_LOGO, a file under public/). */
export const AGENCY_NAME: string = import.meta.env.VITE_AGENCY_NAME || 'PhilHealth';
export const AGENCY_LOGO: string = import.meta.env.VITE_AGENCY_LOGO || '/branding/logo.svg';

type Size = 'sm' | 'md' | 'lg' | 'xl';

/**
 * The agency logo on a white plate, so its black and green artwork stays legible on the green
 * header, the dark display and white cards alike. The logo already contains the agency name, so the
 * name is the image's alternative text rather than a repeated caption.
 */
export function AgencyBrand({ size = 'md' }: { size?: Size }) {
  return (
    <span className={`agency-logo agency-logo-${size}`}>
      <img src={AGENCY_LOGO} alt={AGENCY_NAME} />
    </span>
  );
}
